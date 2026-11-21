cmake_minimum_required(VERSION 3.22)
project(gameinstaller CXX C)
set(CMAKE_CXX_STANDARD 17)

add_library(gameinstaller SHARED native-lib.cpp)
find_library(log-lib log)
target_link_libraries(gameinstaller ${log-lib})

# Підключення сабмодулів, коли вони додані (див. third_party/README.md):
# if(EXISTS ${CMAKE_SOURCE_DIR}/../../../../third_party/unarc)
#   add_subdirectory(...)
# endif()
