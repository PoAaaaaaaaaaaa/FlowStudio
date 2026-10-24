# 检查 so 里有没有未定义的 __aarch64_* 原子符号。
# 有的话 dlopen 会失败，应用起不来 —— 让构建当场失败，别发到用户手上。
execute_process(
    COMMAND ${READELF} --dyn-syms ${SO}
    OUTPUT_VARIABLE SYMS
    ERROR_QUIET
)

string(REGEX MATCHALL "__aarch64_[A-Za-z0-9_]+" ALL_ATOMIC "${SYMS}")

# 逐个确认：出现在 UND 行里的才算未定义
set(UNDEFINED "")
foreach(SYM ${ALL_ATOMIC})
    string(REGEX MATCH "[ \t]UND[ \t]+${SYM}" IS_UNDEF "${SYMS}")
    if(IS_UNDEF)
        list(APPEND UNDEFINED ${SYM})
    endif()
endforeach()

list(REMOVE_DUPLICATES UNDEFINED)
if(UNDEFINED)
    message(FATAL_ERROR "以下原子符号未定义，dlopen 会失败：${UNDEFINED}")
else()
    message(STATUS "原子符号全部已解析")
endif()
