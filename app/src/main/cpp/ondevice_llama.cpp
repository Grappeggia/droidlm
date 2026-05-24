#include <android/log.h>
#include <jni.h>
#include <nlohmann/json.hpp>
#include <sampling.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#include "chat.h"
#include "common.h"
#include "json-schema-to-grammar.h"
#include "llama.h"
#include "log.h"

namespace {
constexpr const char * ERROR_PREFIX = "__DROIDLM_ERROR__:";
constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 6;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int BATCH_SIZE = 512;
constexpr int OVERFLOW_HEADROOM = 8;
constexpr const char * ROLE_SYSTEM = "system";
constexpr const char * ROLE_USER = "user";

llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_batch g_batch = {};
common_chat_templates_ptr g_chat_templates;
bool g_backend_initialized = false;

llama_pos g_system_prompt_position = 0;
llama_pos g_current_position = 0;
llama_pos g_stop_generation_position = 0;
std::vector<common_chat_msg> g_chat_messages;
std::string g_cached_token_chars;
std::ostringstream g_assistant_text;

std::string error_message(const std::string &message) {
    return std::string(ERROR_PREFIX) + message;
}

bool is_valid_utf8(const char *text) {
    if (!text) return true;
    const auto *bytes = reinterpret_cast<const unsigned char *>(text);
    int count = 0;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            count = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            count = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            count = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            count = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < count; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

void clear_generation_state(bool clear_kv_cache = true) {
    g_chat_messages.clear();
    g_system_prompt_position = 0;
    g_current_position = 0;
    g_stop_generation_position = 0;
    g_cached_token_chars.clear();
    g_assistant_text.str("");
    g_assistant_text.clear();
    if (clear_kv_cache && g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

void unload_model() {
    clear_generation_state(false);
    if (g_batch.token != nullptr) {
        llama_batch_free(g_batch);
        g_batch = {};
    }
    g_chat_templates.reset();
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

llama_context * init_context(llama_model *model, int context_size) {
    if (model == nullptr) {
        return nullptr;
    }
    const int n_threads = std::max(
        N_THREADS_MIN,
        std::min(N_THREADS_MAX, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)) - N_THREADS_HEADROOM)
    );
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = context_size;
    context_params.n_batch = BATCH_SIZE;
    context_params.n_ubatch = BATCH_SIZE;
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;
    return llama_init_from_model(model, context_params);
}

std::string format_and_append_message(const std::string &role, const std::string &content) {
    common_chat_msg message;
    message.role = role;
    message.content = content;
    if (common_chat_templates_was_explicit(g_chat_templates.get())) {
        std::string formatted = common_chat_format_single(
            g_chat_templates.get(),
            g_chat_messages,
            message,
            role == ROLE_USER,
            false
        );
        g_chat_messages.push_back(message);
        return formatted;
    }
    g_chat_messages.push_back(message);
    return content;
}

int decode_tokens_in_batches(const llama_tokens &tokens, llama_pos start_pos, bool compute_last_logit = false) {
    for (int start = 0; start < static_cast<int>(tokens.size()); start += BATCH_SIZE) {
        const int batch_size = std::min(static_cast<int>(tokens.size()) - start, BATCH_SIZE);
        common_batch_clear(g_batch);
        for (int index = 0; index < batch_size; ++index) {
            const int absolute_index = start + index;
            const bool want_logit = compute_last_logit && absolute_index == static_cast<int>(tokens.size()) - 1;
            common_batch_add(g_batch, tokens[absolute_index], start_pos + absolute_index, {0}, want_logit);
        }
        if (llama_decode(g_context, g_batch) != 0) {
            return 1;
        }
    }
    return 0;
}

int process_prompt_message(const std::string &role, const std::string &content, bool remember_system_position) {
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    std::string formatted = format_and_append_message(role, content);
    llama_tokens tokens = common_tokenize(g_context, formatted, has_chat_template, has_chat_template);
    if (tokens.empty()) {
        return 0;
    }
    if (decode_tokens_in_batches(tokens, g_current_position, false) != 0) {
        return 1;
    }
    g_current_position += static_cast<llama_pos>(tokens.size());
    if (remember_system_position) {
        g_system_prompt_position = g_current_position;
    }
    return 0;
}

void shift_context() {
    const int discard = (g_current_position - g_system_prompt_position) / 2;
    if (discard <= 0) return;
    llama_memory_seq_rm(llama_get_memory(g_context), 0, g_system_prompt_position, g_system_prompt_position + discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, g_system_prompt_position + discard, g_current_position, -discard);
    g_current_position -= discard;
}

std::string generate_text(
    const std::string &system_prompt,
    const std::string &user_prompt,
    const std::string &json_schema,
    int max_tokens,
    float temperature,
    int top_k,
    float top_p,
    float min_p,
    float presence_penalty
) {
    if (g_model == nullptr || g_context == nullptr) {
        return error_message("Local model is not loaded");
    }

    clear_generation_state();

    common_params_sampling sampling_params;
    sampling_params.temp = temperature;
    sampling_params.top_k = top_k;
    sampling_params.top_p = top_p;
    sampling_params.min_p = min_p;
    sampling_params.penalty_present = presence_penalty;

    if (!json_schema.empty()) {
        try {
            auto schema_json = nlohmann::ordered_json::parse(json_schema);
            sampling_params.grammar = common_grammar(
                COMMON_GRAMMAR_TYPE_USER,
                json_schema_to_grammar(schema_json, true)
            );
        } catch (const std::exception &e) {
            return error_message(std::string("Could not prepare JSON grammar: ") + e.what());
        }
    }

    std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
        common_sampler_init(g_model, sampling_params),
        common_sampler_free
    );
    if (!sampler) {
        return error_message("Could not initialize sampler");
    }

    if (!system_prompt.empty() && process_prompt_message(ROLE_SYSTEM, system_prompt, true) != 0) {
        return error_message("Could not process the local planner system prompt");
    }
    if (process_prompt_message(ROLE_USER, user_prompt, false) != 0) {
        return error_message("Could not process the local planner user prompt");
    }

    g_stop_generation_position = g_current_position + std::max(1, max_tokens);

    while (g_current_position < g_stop_generation_position) {
        if (g_current_position >= llama_n_ctx(g_context) - OVERFLOW_HEADROOM) {
            shift_context();
        }

        const llama_token new_token_id = common_sampler_sample(sampler.get(), g_context, -1);
        common_sampler_accept(sampler.get(), new_token_id, true);

        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token_id, g_current_position, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            return error_message("Local planner token decode failed");
        }
        g_current_position += 1;

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
            break;
        }

        g_cached_token_chars += common_token_to_piece(g_context, new_token_id);
        if (is_valid_utf8(g_cached_token_chars.c_str())) {
            g_assistant_text << g_cached_token_chars;
            g_cached_token_chars.clear();
        }
    }

    if (!g_cached_token_chars.empty() && is_valid_utf8(g_cached_token_chars.c_str())) {
        g_assistant_text << g_cached_token_chars;
    }

    return g_assistant_text.str();
}
} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_ai_droidlm_ondevice_LlamaOnDeviceEngine_nativeInit(JNIEnv *env, jobject, jstring native_lib_dir) {
    if (g_backend_initialized) {
        return;
    }
    const char *path = env->GetStringUTFChars(native_lib_dir, nullptr);
    llama_log_set(common_log_default_callback, nullptr);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(native_lib_dir, path);
    llama_backend_init();
    g_backend_initialized = true;
}

extern "C"
JNIEXPORT jint JNICALL
Java_ai_droidlm_ondevice_LlamaOnDeviceEngine_nativeLoadModel(JNIEnv *env, jobject, jstring model_path, jint context_size) {
    unload_model();
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (g_model == nullptr) {
        return 1;
    }
    g_context = init_context(g_model, context_size);
    if (g_context == nullptr) {
        unload_model();
        return 2;
    }
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    clear_generation_state();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_ai_droidlm_ondevice_LlamaOnDeviceEngine_nativeGenerateJson(
    JNIEnv *env,
    jobject,
    jstring system_prompt,
    jstring user_prompt,
    jstring json_schema,
    jint max_tokens,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat min_p,
    jfloat presence_penalty
) {
    const char *system_chars = env->GetStringUTFChars(system_prompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(user_prompt, nullptr);
    const char *schema_chars = env->GetStringUTFChars(json_schema, nullptr);
    std::string result = generate_text(
        system_chars,
        user_chars,
        schema_chars,
        max_tokens,
        temperature,
        top_k,
        top_p,
        min_p,
        presence_penalty
    );
    env->ReleaseStringUTFChars(system_prompt, system_chars);
    env->ReleaseStringUTFChars(user_prompt, user_chars);
    env->ReleaseStringUTFChars(json_schema, schema_chars);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_droidlm_ondevice_LlamaOnDeviceEngine_nativeUnloadModel(JNIEnv *, jobject) {
    unload_model();
}

extern "C"
JNIEXPORT void JNICALL
Java_ai_droidlm_ondevice_LlamaOnDeviceEngine_nativeShutdown(JNIEnv *, jobject) {
    unload_model();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
