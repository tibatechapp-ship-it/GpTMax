#include "generation-budget.h"

#include <cassert>
#include <cstdint>

int main() {
    using gptmax::inference::effective_generation_tokens;
    assert(effective_generation_tokens(100, 2048, 384) == 384);
    assert(effective_generation_tokens(1800, 2048, 384) == 248);
    assert(effective_generation_tokens(2047, 2048, 384) == 1);
    assert(effective_generation_tokens(2048, 2048, 384) == 0);
    assert(effective_generation_tokens(10, 0, 384) == 0);
    assert(effective_generation_tokens(10, 2048, 0) == 1);
    assert(effective_generation_tokens(10, 2048, 4096) == 1024);
    assert(effective_generation_tokens(0, 16, -4) == 1);
    return 0;
}
