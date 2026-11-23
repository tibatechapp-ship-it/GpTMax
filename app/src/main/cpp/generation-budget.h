#pragma once

#include <algorithm>
#include <cstdint>

namespace gptmax::inference {

// Returns the largest safe output budget that fits in the current context.
// A zero result means the prompt leaves no token slot for generation.
inline int32_t effective_generation_tokens(
        uint64_t prompt_tokens, uint32_t context_tokens, int32_t requested_tokens) {
    if (context_tokens == 0 || prompt_tokens >= context_tokens) return 0;
    const uint64_t available = static_cast<uint64_t>(context_tokens) - prompt_tokens;
    const int32_t requested = std::clamp(requested_tokens, 1, 1024);
    return static_cast<int32_t>(std::min<uint64_t>(
            available, static_cast<uint64_t>(requested)));
}

} // namespace gptmax::inference
