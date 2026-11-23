package com.andrewchqng.htop.shizuku;

interface IShizukuPtyCallback {
    void onOutput(in byte[] data, int length) = 1;
    void onExit(int exitCode) = 2;
}
