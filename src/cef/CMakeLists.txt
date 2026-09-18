# Official Windows CEF distributions use the MSVC ABI, not MinGW's ABI.
if(NOT MSVC)
  message(FATAL_ERROR "CEF requires an MSVC-compatible toolchain. Native C++17 builds still support MinGW.")
endif()
set(CEF_ROOT "" CACHE PATH "Unpacked current Windows CEF binary distribution")
if(NOT EXISTS "${CEF_ROOT}/cmake/FindCEF.cmake")
  message(FATAL_ERROR "Set CEF_ROOT to a current Windows CEF SDK; no SDK is downloaded or downgraded automatically.")
endif()
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CEF_ROOT}/cmake")
set(CEF_RUNTIME_LIBRARY_FLAG "/MT")
find_package(CEF REQUIRED)
add_subdirectory("${CEF_LIBCEF_DLL_WRAPPER_PATH}" "${CMAKE_CURRENT_BINARY_DIR}/libcef_dll_wrapper")
set_target_properties(libcef_dll_wrapper PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS NO)
ADD_LOGICAL_TARGET(libcef_lib "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")

add_library(cef_bridge STATIC bridge.cpp)
musxi_standard(cef_bridge 20)
SET_LIBRARY_TARGET_PROPERTIES(cef_bridge)
target_include_directories(cef_bridge PRIVATE "${CEF_ROOT}")
target_link_libraries(cef_bridge PRIVATE music_application libcef_dll_wrapper libcef_lib ${CEF_STANDARD_LIBS})

# Build-only host adapter. Browser window, IPC and lifecycle wiring follow later.
# Do not replace the working native executable with an incomplete CEF host.
add_library(cef_host STATIC host.cpp)
musxi_standard(cef_host 20)
SET_LIBRARY_TARGET_PROPERTIES(cef_host)
target_include_directories(cef_host PRIVATE "${CEF_ROOT}")
target_link_libraries(cef_host PRIVATE cef_bridge libcef_dll_wrapper libcef_lib ${CEF_STANDARD_LIBS})
