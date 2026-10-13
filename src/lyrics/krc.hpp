#pragma once
#include "cache.hpp"
#include <string_view>

namespace musxi::lyrics {
struct KrcLyrics {
    std::string original,translation; // Normalized timed text for the existing v2 stores.
    Translation state=Translation::Unknown;
};
// Input is already decrypted/inflated UTF-8. Translation metadata failures keep
// the original; indexes are resolved before sorting, dropping blanks or clamping.
KrcLyrics extractKrc(std::string_view text);
// Strict text + timeline compatibility for adding KRC translations to an older
// LRC cache/snapshot. This deliberately prefers a missed translation to a mismatch.
bool sameOriginal(std::string_view first,std::string_view second);
// Retimes the verified one-to-one KRC rows to the target LRC before the generic
// nearest-time matcher, so closely spaced/rounded rows cannot exchange translations.
bool retimeKrc(KrcLyrics& lyrics,std::string_view target);
}
