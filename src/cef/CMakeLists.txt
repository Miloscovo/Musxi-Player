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
set(USE_SANDBOX ON CACHE BOOL "Use SDK bootstrap sandbox" FORCE)
set(USE_ATL OFF CACHE BOOL "ATL not needed" FORCE)
find_package(CEF REQUIRED)
execute_process(COMMAND "${MUSXI_POWERSHELL}" -NoProfile -ExecutionPolicy Bypass
  -File "${PROJECT_SOURCE_DIR}/verify-ffmpeg.ps1" -FfmpegRoot "${FFMPEG_ROOT}" -CefRoot "${CEF_ROOT}"
  RESULT_VARIABLE cef_ffmpeg_audit_result)
if(NOT cef_ffmpeg_audit_result EQUAL 0)
  message(FATAL_ERROR "CEF embedded FFmpeg distribution evidence check failed.")
endif()
add_subdirectory("${CEF_LIBCEF_DLL_WRAPPER_PATH}" "${CMAKE_CURRENT_BINARY_DIR}/libcef_dll_wrapper")
set_target_properties(libcef_dll_wrapper PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS NO)
ADD_LOGICAL_TARGET(libcef_lib "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")

add_library(cef_bridge STATIC bridge.cpp)
musxi_standard(cef_bridge 20)
SET_LIBRARY_TARGET_PROPERTIES(cef_bridge)
target_include_directories(cef_bridge PRIVATE "${CEF_ROOT}")
target_link_libraries(cef_bridge PRIVATE music_application libcef_dll_wrapper libcef_lib ${CEF_STANDARD_LIBS} comctl32)

# CEF is the application host; Core and Application remain C++17 targets.
add_library(cef_host SHARED host.cpp)
musxi_standard(cef_host 20)
SET_LIBRARY_TARGET_PROPERTIES(cef_host)
target_include_directories(cef_host PRIVATE "${CEF_ROOT}")
target_link_libraries(cef_host PRIVATE cef_bridge libcef_dll_wrapper libcef_lib ${CEF_STANDARD_LIBS} comctl32 shlwapi imm32 d3d11 dcomp)
if(MUSXI_ENABLE_FFMPEG)
  target_compile_definitions(cef_host PRIVATE MUSXI_ENABLE_FFMPEG)
  add_custom_command(TARGET cef_host POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
    ${FFmpeg_RUNTIME_FILES} "$<TARGET_FILE_DIR:cef_host>" COMMAND_EXPAND_LISTS)
endif()
set_target_properties(cef_host PROPERTIES OUTPUT_NAME MusxiPlayerWeb)
set(CEF_TARGET cef_host)
SET_CEF_TARGET_OUT_DIR()
set_target_properties(cef_host PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CEF_TARGET_OUT_DIR}")
COPY_SINGLE_FILE(cef_host "${CEF_BINARY_DIR}/bootstrap.exe" "${CEF_TARGET_OUT_DIR}/MusxiPlayerWeb.exe")
COPY_FILES(cef_host "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR}" "${CEF_TARGET_OUT_DIR}")
COPY_FILES(cef_host "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${CEF_TARGET_OUT_DIR}")
option(MUSXI_BUILD_VUE_UI "Build and deploy the Vue application" ON)
add_custom_target(cef_ui_assets ALL
  COMMAND ${CMAKE_COMMAND} -E copy_directory
          "${CMAKE_SOURCE_DIR}/frontend/phase2" "${CEF_TARGET_OUT_DIR}/ui"
  COMMAND ${CMAKE_COMMAND} -E copy_if_different
          "${CMAKE_SOURCE_DIR}/LICENSE" "${CMAKE_SOURCE_DIR}/THIRD_PARTY_NOTICES.md" "${CEF_TARGET_OUT_DIR}"
  COMMAND ${CMAKE_COMMAND} -E copy_directory
          "${CMAKE_SOURCE_DIR}/licenses" "${CEF_TARGET_OUT_DIR}/licenses")
if(MUSXI_BUILD_VUE_UI)
  find_program(MUSXI_NPM NAMES npm.cmd npm REQUIRED)
  add_custom_target(vue_ui
    COMMAND "${MUSXI_NPM}" run build
    WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}/frontend"
    VERBATIM)
  add_dependencies(cef_ui_assets vue_ui)
  add_custom_command(TARGET cef_ui_assets POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CMAKE_SOURCE_DIR}/frontend/dist" "${CEF_TARGET_OUT_DIR}/ui-vue")
endif()
add_dependencies(cef_host cef_ui_assets)
SET_LPAC_ACLS(cef_host)
foreach(asset services runtime)
  if(EXISTS "${CMAKE_SOURCE_DIR}/build/${asset}")
    add_custom_command(TARGET cef_host POST_BUILD
      COMMAND ${CMAKE_COMMAND} -E copy_directory
              "${CMAKE_SOURCE_DIR}/build/${asset}" "${CEF_TARGET_OUT_DIR}/${asset}")
  endif()
endforeach()
