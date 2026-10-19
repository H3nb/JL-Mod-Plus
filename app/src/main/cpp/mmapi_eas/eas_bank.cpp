// SPDX-License-Identifier: Apache-2.0
#include "eas_bank.h"
#include "eas_file.h"
#include <new>
#include <stdexcept>

namespace mmapi::eas {
namespace {
void checked(EAS_RESULT result, const char *operation) {
    if (result == EAS_ERROR_MALLOC_FAILED) throw std::bad_alloc();
    if (result != EAS_SUCCESS)
        throw std::runtime_error(std::string(operation) + ": EAS " + std::to_string(result));
}
}
SharedBank::SharedBank(const std::string &path) {
    checked(EAS_Init(&owner), "Initialize bank loader");
    try {
        checked(EAS_SetHeaderSearchFlag(owner, EAS_FALSE), "Configure bank loader");
        DiskFile file(path);
        checked(EAS_LoadDLSCollection(owner, nullptr, &file.locator), "Load synthesis bank");
    } catch (...) {
        EAS_Shutdown(owner);
        owner = nullptr;
        throw;
    }
}
SharedBank::~SharedBank() { if (owner) EAS_Shutdown(owner); }
std::shared_ptr<SharedBank> BankPool::acquire(const std::string &path) {
    // Bank parsing is off the audio callback. Serializing cold loads also
    // prevents two simultaneous players from allocating duplicate large banks.
    std::lock_guard<std::mutex> guard(lock);
    auto &entry = entries[path];
    if (auto bank = entry.lock()) return bank;
    auto bank = std::make_shared<SharedBank>(path);
    entry = bank;
    return bank;
}
}
