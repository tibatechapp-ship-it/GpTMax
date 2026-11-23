#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace gptmax::unicode {

inline void append_utf8(std::string & out, uint32_t cp) {
    if (cp <= 0x7f) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
}

inline bool utf16_to_utf8(const char16_t * input, size_t length, std::string & output) {
    try {
        output.clear();
        output.reserve(length * 3);
        for (size_t i = 0; i < length; ++i) {
            uint32_t cp = input[i];
            if (cp >= 0xd800 && cp <= 0xdbff) {
                if (i + 1 < length && input[i + 1] >= 0xdc00 && input[i + 1] <= 0xdfff) {
                    cp = 0x10000 + ((cp - 0xd800) << 10) +
                         (static_cast<uint32_t>(input[++i]) - 0xdc00);
                } else {
                    cp = 0xfffd;
                }
            } else if (cp >= 0xdc00 && cp <= 0xdfff) {
                cp = 0xfffd;
            }
            append_utf8(output, cp);
        }
        return true;
    } catch (...) {
        output.clear();
        return false;
    }
}

inline bool utf8_to_utf16(const std::string & input, std::u16string & output) {
    try {
        output.clear();
        output.reserve(input.size());
        size_t i = 0;
        while (i < input.size()) {
            const uint8_t lead = static_cast<uint8_t>(input[i]);
            uint32_t cp = 0xfffd;
            size_t width = 1;
            if (lead <= 0x7f) {
                cp = lead;
            } else if (lead >= 0xc2 && lead <= 0xdf && i + 1 < input.size()) {
                const uint8_t b1 = static_cast<uint8_t>(input[i + 1]);
                if ((b1 & 0xc0) == 0x80) {
                    cp = ((lead & 0x1f) << 6) | (b1 & 0x3f);
                    width = 2;
                }
            } else if (lead >= 0xe0 && lead <= 0xef && i + 2 < input.size()) {
                const uint8_t b1 = static_cast<uint8_t>(input[i + 1]);
                const uint8_t b2 = static_cast<uint8_t>(input[i + 2]);
                const bool second_ok = (b1 & 0xc0) == 0x80 &&
                        !(lead == 0xe0 && b1 < 0xa0) &&
                        !(lead == 0xed && b1 >= 0xa0);
                if (second_ok && (b2 & 0xc0) == 0x80) {
                    cp = ((lead & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f);
                    width = 3;
                }
            } else if (lead >= 0xf0 && lead <= 0xf4 && i + 3 < input.size()) {
                const uint8_t b1 = static_cast<uint8_t>(input[i + 1]);
                const uint8_t b2 = static_cast<uint8_t>(input[i + 2]);
                const uint8_t b3 = static_cast<uint8_t>(input[i + 3]);
                const bool second_ok = (b1 & 0xc0) == 0x80 &&
                        !(lead == 0xf0 && b1 < 0x90) &&
                        !(lead == 0xf4 && b1 > 0x8f);
                if (second_ok && (b2 & 0xc0) == 0x80 && (b3 & 0xc0) == 0x80) {
                    cp = ((lead & 0x07) << 18) | ((b1 & 0x3f) << 12) |
                         ((b2 & 0x3f) << 6) | (b3 & 0x3f);
                    width = 4;
                }
            }
            i += width;
            if (cp <= 0xffff) {
                output.push_back(static_cast<char16_t>(cp));
            } else {
                cp -= 0x10000;
                output.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
                output.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
            }
        }
        return true;
    } catch (...) {
        output.clear();
        return false;
    }
}

} // namespace gptmax::unicode
