// SPDX-License-Identifier: Apache-2.0
#ifndef MMAPI_EAS_BANK_H
#define MMAPI_EAS_BANK_H
#include "eas.h"
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace mmapi::eas {
// Holds one Sonivox collection and its owner context for its entire lifetime.
// Playback contexts reference the loaded collection, never the owner voices.
class SharedBank final {
    EAS_DATA_HANDLE owner = nullptr;
public:
    explicit SharedBank(const std::string &path);
    ~SharedBank();
    SharedBank(const SharedBank &) = delete;
    SharedBank &operator=(const SharedBank &) = delete;
    EAS_DATA_HANDLE context() const { return owner; }
};
// The session registry owns the weak pool identity; active Players own the pool
// and their chosen bank. Expired banks are reclaimed as soon as the last Player
// releases them, rather than being pinned for the entire session.
class BankPool final {
    std::mutex lock;
    std::unordered_map<std::string, std::weak_ptr<SharedBank>> entries;
public:
    std::shared_ptr<SharedBank> acquire(const std::string &path);
};
}
#endif
