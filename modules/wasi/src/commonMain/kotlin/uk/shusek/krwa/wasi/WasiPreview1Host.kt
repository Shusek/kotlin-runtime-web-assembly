package uk.shusek.krwa.wasi

import uk.shusek.krwa.runtime.Memory

interface WasiPreview1Host {
    fun adapterCloseBadfd(fd: Int): Int

    fun adapterOpenBadfd(fd: Int): Int

    fun argsGet(memory: Memory, argvStart: Int, argvBufStart: Int): Int

    fun argsSizesGet(memory: Memory, argc: Int, argvBufSize: Int): Int

    fun clockResGet(memory: Memory, clockId: Int, resultPtr: Int): Int

    fun clockTimeGet(memory: Memory, clockId: Int, precision: Long, resultPtr: Int): Int

    fun environGet(memory: Memory, environStart: Int, environBufStart: Int): Int

    fun environSizesGet(memory: Memory, environCount: Int, environBufSize: Int): Int

    fun fdAdvise(fd: Int, offset: Long, len: Long, advice: Int): Int

    fun fdAllocate(fd: Int, offset: Long, len: Long): Int

    fun fdClose(fd: Int): Int

    fun fdDatasync(fd: Int): Int

    fun fdFdstatGet(memory: Memory, fd: Int, buf: Int): Int

    fun fdFdstatSetFlags(fd: Int, flags: Int): Int

    fun fdFdstatSetRights(fd: Int, rightsBase: Long, rightsInheriting: Long): Int

    fun fdFilestatGet(memory: Memory, fd: Int, buf: Int): Int

    fun fdFilestatSetSize(fd: Int, size: Long): Int

    fun fdFilestatSetTimes(fd: Int, accessTime: Long, modifiedTime: Long, fstFlags: Int): Int

    fun fdPread(
        memory: Memory,
        fd: Int,
        iovs: Int,
        iovsLen: Int,
        offsetStart: Long,
        nreadPtr: Int,
    ): Int

    fun fdPrestatDirName(memory: Memory, fd: Int, path: Int, pathLen: Int): Int

    fun fdPrestatGet(memory: Memory, fd: Int, buf: Int): Int

    fun fdPwrite(
        memory: Memory,
        fd: Int,
        iovs: Int,
        iovsLen: Int,
        offsetStart: Long,
        nwrittenPtr: Int,
    ): Int

    fun fdRead(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, nreadPtr: Int): Int

    fun fdReaddir(
        memory: Memory,
        dirFd: Int,
        buf: Int,
        bufLen: Int,
        cookieStart: Long,
        bufUsedPtr: Int,
    ): Int

    fun fdRenumber(from: Int, to: Int): Int

    fun fdSeek(memory: Memory, fd: Int, offset: Long, whence: Int, newOffsetPtr: Int): Int

    fun fdSync(fd: Int): Int

    fun fdTell(memory: Memory, fd: Int, offsetPtr: Int): Int

    fun fdWrite(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, nwrittenPtr: Int): Int

    fun pathCreateDirectory(dirFd: Int, rawPath: String): Int

    fun pathFilestatGet(
        memory: Memory,
        dirFd: Int,
        lookupFlags: Int,
        rawPath: String,
        buf: Int,
    ): Int

    fun pathFilestatSetTimes(
        dirFd: Int,
        lookupFlags: Int,
        rawPath: String,
        accessTime: Long,
        modifiedTime: Long,
        fstFlags: Int,
    ): Int

    fun pathLink(
        oldFd: Int,
        lookupFlags: Int,
        rawOldPath: String,
        newFd: Int,
        rawNewPath: String,
    ): Int

    fun pathOpen(
        memory: Memory,
        dirFd: Int,
        lookupFlags: Int,
        rawPath: String,
        openFlags: Int,
        rightsBase: Long,
        rightsInheriting: Long,
        fdFlags: Int,
        retFdPtr: Int,
    ): Int

    fun pathReadlink(
        memory: Memory,
        dirFd: Int,
        rawPath: String,
        buf: Int,
        bufLen: Int,
        resultPtr: Int,
    ): Int

    fun pathRemoveDirectory(dirFd: Int, rawPath: String): Int

    fun pathRename(oldFd: Int, oldRawPath: String, newFd: Int, newRawPath: String): Int

    fun pathSymlink(oldRawPath: String, dirFd: Int, newRawPath: String): Int

    fun pathUnlinkFile(dirFd: Int, rawPath: String): Int

    fun pollOneoff(
        memory: Memory,
        inPtrStart: Int,
        outPtrStart: Int,
        nsubscriptions: Int,
        neventsPtr: Int,
    ): Int

    fun procExit(code: Int)

    fun procRaise(sig: Int): Int

    fun randomGet(memory: Memory, buf: Int, bufLen: Int): Int

    fun schedYield(): Int

    fun sockAccept(sock: Int, fdFlags: Int, roFdPtr: Int): Int

    fun sockRecv(
        sock: Int,
        riDataPtr: Int,
        riDataLen: Int,
        riFlags: Int,
        roDataLenPtr: Int,
        roFlagsPtr: Int,
    ): Int

    fun sockSend(sock: Int, siDataPtr: Int, siDataLen: Int, siFlags: Int, retDataLenPtr: Int): Int

    fun sockShutdown(sock: Int, how: Int): Int
}
