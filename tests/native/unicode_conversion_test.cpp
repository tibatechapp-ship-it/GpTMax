#include "unicode-conversion.h"

#include <cassert>
#include <iostream>
#include <string>

int main() {
    using gptmax::unicode::utf16_to_utf8;
    using gptmax::unicode::utf8_to_utf16;

    const std::u16string arabic = u"مرحبا بالعالم";
    std::string encoded;
    std::u16string decoded;
    assert(utf16_to_utf8(arabic.data(), arabic.size(), encoded));
    assert(utf8_to_utf16(encoded, decoded));
    assert(decoded == arabic);

    const std::u16string mixed = u"English العربية 😀 🚀";
    assert(utf16_to_utf8(mixed.data(), mixed.size(), encoded));
    assert(utf8_to_utf16(encoded, decoded));
    assert(decoded == mixed);

    const std::u16string supplementary = {u'A', 0xd83d, 0xde00, u'Z'};
    assert(utf16_to_utf8(supplementary.data(), supplementary.size(), encoded));
    assert(utf8_to_utf16(encoded, decoded));
    assert(decoded == supplementary);

    const std::u16string isolated_high = {0xd800};
    assert(utf16_to_utf8(isolated_high.data(), isolated_high.size(), encoded));
    assert(encoded == "\xef\xbf\xbd");

    const std::u16string isolated_low = {0xdc00};
    assert(utf16_to_utf8(isolated_low.data(), isolated_low.size(), encoded));
    assert(encoded == "\xef\xbf\xbd");

    assert(utf8_to_utf16(std::string("\xc0\xaf", 2), decoded));
    assert(decoded == u"\ufffd\ufffd");

    assert(utf8_to_utf16(std::string("\xed\xa0\x80", 3), decoded));
    assert(decoded == u"\ufffd\ufffd\ufffd");

    assert(utf8_to_utf16(std::string("\xf4\x90\x80\x80", 4), decoded));
    assert(decoded == u"\ufffd\ufffd\ufffd\ufffd");

    std::cout << "Unicode conversion tests passed\n";
    return 0;
}
