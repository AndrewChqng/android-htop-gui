cmake_minimum_required(VERSION 3.22.1)
project(htoppty C)

add_library(htoppty SHARED pty.c)

target_compile_options(htoppty PRIVATE -Wall -Wextra -Werror=implicit-function-declaration -O2)
target_link_options(htoppty PRIVATE -Wl,-z,max-page-size=16384)

find_library(log-lib log)
target_link_libraries(htoppty ${log-lib})
