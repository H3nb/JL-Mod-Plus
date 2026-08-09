//
// Created by woesss on 09.07.2023.
//

#include <cstring>
#include "eas_file.h"

namespace mmapi {
    namespace eas {

        BaseFile::~BaseFile() {}

        int BaseFile::size(void *handle) {
            return static_cast<BaseFile *>(handle)->length;
        }

        int BaseFile::readAt(void *handle, void *buf, int offset, int size) {
            return static_cast<BaseFile *>(handle)->readAt(buf, offset, size);
        }

        IOFile::IOFile(const char *path, const char *const mode) {
            if (path == nullptr || mode == nullptr) {
                return;
            }
            file = fopen(path, mode);
            if (file == nullptr) {
                return;
            }
            if (fseek(file, 0, SEEK_END) != 0) {
                fclose(file);
                file = nullptr;
                return;
            }
            long fileLength = ftell(file);
            if (fileLength < 0 || fseek(file, 0, SEEK_SET) != 0) {
                fclose(file);
                file = nullptr;
                return;
            }
            length = static_cast<size_t>(fileLength);
        }

        IOFile::~IOFile() {
            if (file != nullptr) {
                fclose(file);
            }
        }

        bool IOFile::isOpen() const {
            return file != nullptr;
        }

        int IOFile::readAt(void *buf, int offset, int size) {
            if (file == nullptr || buf == nullptr || offset < 0 || size < 0) {
                return -1;
            }
            if (fseek(file, offset, SEEK_SET) != 0) {
                return -1;
            }
            return static_cast<int>(fread(buf, 1, static_cast<size_t>(size), file));
        }

        MemFile::MemFile(JNIEnv *env, jbyteArray array) {
            length = env->GetArrayLength(array);
            char *buf = new char[length];
            env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(buf));
            data = buf;
        }

        MemFile::~MemFile() {
            delete[] data;
        }

        int MemFile::readAt(void *buf, int offset, int size) {
            if (offset < 0 || offset >= length || size < 0) {
                return -1;
            }
            if (size > length - offset) {
                size = length - offset;
            }
            memcpy(buf, data + offset, size);
            return size;
        }
    } // namespace eas
} // namespace mmapi
