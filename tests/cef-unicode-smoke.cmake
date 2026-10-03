# Exercise real navigation, IPC and reload with non-ASCII/spaced file paths.
get_filename_component(runtime "${HOST}" DIRECTORY)
file(MAKE_DIRECTORY "${runtime}/测试 空格")
file(COPY "${runtime}/ui-react/index.html" "${runtime}/ui-react/app.js"
          "${runtime}/ui-react/style.css" DESTINATION "${runtime}/测试 空格")
execute_process(COMMAND "${HOST}" --test-app --cef-smoke --cef-smoke-unicode
                RESULT_VARIABLE result TIMEOUT 35)
if(NOT result STREQUAL "0")
  message(FATAL_ERROR "Unicode-path CEF smoke failed: ${result}")
endif()
