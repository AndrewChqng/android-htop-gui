package com.andrewchqng.htop.shizuku;

import com.andrewchqng.htop.shizuku.IShizukuPtyCallback;

interface IShizukuPtyService {
    boolean start(
        String command,
        in String[] envp,
        int rows,
        int cols,
        int xpixel,
        int ypixel,
        IShizukuPtyCallback callback
    ) = 1;
    void write(in byte[] data) = 2;
    void resize(int rows, int cols, int xpixel, int ypixel) = 3;
    void stopSession() = 4;
    int getUid() = 5;
    void destroy() = 16777114;
}
