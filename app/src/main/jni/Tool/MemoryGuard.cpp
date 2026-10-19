#include "MemoryGuard.h"
#include <sys/uio.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace MemoryGuard
{
    bool SafeRead(const void *src, void *dst, size_t size)
    {
        if (!src || !dst || size == 0)
            return false;

        struct iovec local_iov;
        local_iov.iov_base = dst;
        local_iov.iov_len = size;

        struct iovec remote_iov;
        remote_iov.iov_base = const_cast<void *>(src);
        remote_iov.iov_len = size;

        ssize_t bytesRead = syscall(__NR_process_vm_readv, getpid(), &local_iov, 1, &remote_iov, 1, 0);
        return (bytesRead == static_cast<ssize_t>(size));
    }

    bool SafeWrite(void *dst, const void *src, size_t size)
    {
        if (!src || !dst || size == 0)
            return false;

        struct iovec local_iov;
        local_iov.iov_base = const_cast<void *>(src);
        local_iov.iov_len = size;

        struct iovec remote_iov;
        remote_iov.iov_base = dst;
        remote_iov.iov_len = size;

        ssize_t bytesWritten = syscall(__NR_process_vm_writev, getpid(), &local_iov, 1, &remote_iov, 1, 0);
        return (bytesWritten == static_cast<ssize_t>(size));
    }

    bool IsValidReadPtr(const void *ptr, size_t size)
    {
        if (!ptr)
            return false;

        char dummy[1];
        struct iovec local_iov;
        local_iov.iov_base = dummy;
        local_iov.iov_len = 1;

        struct iovec remote_iov;
        remote_iov.iov_base = const_cast<void *>(ptr);
        remote_iov.iov_len = 1;

        return (syscall(__NR_process_vm_readv, getpid(), &local_iov, 1, &remote_iov, 1, 0) == 1);
    }

    std::string SafeReadString(const void *src, size_t maxLen)
    {
        if (!src)
            return "";

        std::string res;
        res.reserve(64);
        char c = '\0';
        const char *p = static_cast<const char *>(src);
        for (size_t i = 0; i < maxLen; ++i)
        {
            if (!SafeReadVal(p + i, c))
                break;
            if (c == '\0')
                break;
            res.push_back(c);
        }
        return res;
    }
}
