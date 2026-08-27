#include <jni.h>
#include <sys/uio.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>

namespace {

bool mappingContains(pid_t pid, uintptr_t address, bool *readable, bool *writable) {
    std::ifstream maps("/proc/" + std::to_string(pid) + "/maps");
    if (!maps.is_open()) return false;
    std::string line;
    while (std::getline(maps, line)) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char perms[5] = {};
        if (sscanf(line.c_str(), "%llx-%llx %4s", &start, &end, perms) != 3) continue;
        if (address < start || address >= end) continue;
        if (readable != nullptr) *readable = perms[0] == 'r';
        if (writable != nullptr) *writable = perms[1] == 'w';
        return true;
    }
    return false;
}

long targetUid(pid_t pid) {
    std::ifstream status("/proc/" + std::to_string(pid) + "/status");
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind("Uid:", 0) != 0) continue;
        unsigned long real_uid = 0;
        if (sscanf(line.c_str(), "Uid:\t%lu", &real_uid) == 1) {
            return static_cast<long>(real_uid);
        }
    }
    return -1;
}

ssize_t remoteRead(pid_t pid, uintptr_t address, void *buffer, size_t size) {
    iovec local{buffer, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_readv(pid, &local, 1, &remote, 1, 0);
}

ssize_t remoteWrite(pid_t pid, uintptr_t address, const void *buffer, size_t size) {
    iovec local{const_cast<void *>(buffer), size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_writev(pid, &local, 1, &remote, 1, 0);
}

std::string errnoText() {
    const int value = errno;
    std::ostringstream out;
    out << value << " (" << strerror(value) << ')';
    return out.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ru_playsoftware_j2meloader_memory_NativeRemoteMemoryEngine_capabilityTest(
        JNIEnv *env, jclass, jint target_pid, jlong probe_address, jlong expected_value) {
    std::ostringstream out;
    const pid_t pid = static_cast<pid_t>(target_pid);
    const uintptr_t address = static_cast<uintptr_t>(probe_address);
    const uint64_t expected = static_cast<uint64_t>(expected_value);
    const long target_uid = targetUid(pid);
    const long engine_uid = static_cast<long>(getuid());

    out << "remoteEnginePid=" << getpid()
        << "\nremoteTargetPid=" << pid
        << "\nremoteEngineUid=" << engine_uid
        << "\nremoteTargetUid=" << target_uid
        << "\nremoteSameUid=" << (target_uid >= 0 && target_uid == engine_uid ? "true" : "false")
        << "\nremoteSameProcess=" << (pid == getpid() ? "true" : "false");

    if (pid <= 0 || address == 0 || pid == getpid()) {
        out << "\nremoteMaps=FAIL"
            << "\nremoteRead=FAIL"
            << "\nremoteWrite=FAIL"
            << "\nremoteReadback=FAIL"
            << "\nremoteRestore=FAIL"
            << "\nremoteEngineSupported=false"
            << "\nremoteError=invalid target/probe";
        const std::string text = out.str();
        return env->NewStringUTF(text.c_str());
    }

    bool readable = false;
    bool writable = false;
    const bool mapped = mappingContains(pid, address, &readable, &writable);
    out << "\nremoteMaps=" << (mapped ? "PASS" : "FAIL")
        << "\nremoteMappingReadable=" << (readable ? "true" : "false")
        << "\nremoteMappingWritable=" << (writable ? "true" : "false");

    uint64_t before = 0;
    errno = 0;
    const ssize_t read_before = remoteRead(pid, address, &before, sizeof(before));
    const bool read_ok = read_before == static_cast<ssize_t>(sizeof(before));
    out << "\nremoteRead=" << (read_ok ? "PASS" : "FAIL");
    if (!read_ok) out << "\nremoteReadErrno=" << errnoText();
    out << "\nremoteProbeMatches=" << (read_ok && before == expected ? "true" : "false");

    bool write_ok = false;
    bool readback_ok = false;
    bool restore_ok = false;
    if (read_ok && before == expected && mapped && readable && writable) {
        const uint64_t mutated = expected ^ 0x13579BDF2468ACE0ULL;
        errno = 0;
        const ssize_t wrote = remoteWrite(pid, address, &mutated, sizeof(mutated));
        write_ok = wrote == static_cast<ssize_t>(sizeof(mutated));
        if (!write_ok) out << "\nremoteWriteErrno=" << errnoText();

        uint64_t verify = 0;
        if (write_ok) {
            errno = 0;
            readback_ok = remoteRead(pid, address, &verify, sizeof(verify))
                    == static_cast<ssize_t>(sizeof(verify)) && verify == mutated;
            if (!readback_ok) out << "\nremoteReadbackErrno=" << errnoText();
        }

        if (write_ok) {
            errno = 0;
            const ssize_t restored = remoteWrite(pid, address, &expected, sizeof(expected));
            uint64_t restored_value = 0;
            restore_ok = restored == static_cast<ssize_t>(sizeof(expected))
                    && remoteRead(pid, address, &restored_value, sizeof(restored_value))
                            == static_cast<ssize_t>(sizeof(restored_value))
                    && restored_value == expected;
            if (!restore_ok) out << "\nremoteRestoreErrno=" << errnoText();
        }
    }

    out << "\nremoteWrite=" << (write_ok ? "PASS" : "FAIL")
        << "\nremoteReadback=" << (readback_ok ? "PASS" : "FAIL")
        << "\nremoteRestore=" << (restore_ok ? "PASS" : "FAIL");
    const bool supported = target_uid >= 0 && target_uid == engine_uid
            && mapped && readable && writable && read_ok && before == expected
            && write_ok && readback_ok && restore_ok;
    out << "\nremoteEngineSupported=" << (supported ? "true" : "false");

    const std::string text = out.str();
    return env->NewStringUTF(text.c_str());
}
