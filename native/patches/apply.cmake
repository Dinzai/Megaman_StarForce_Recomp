# Applies PATCH to the current directory once; a no-op when it is already applied,
# so reconfiguring an existing FetchContent checkout doesn't fail.
execute_process(COMMAND git apply --reverse --check --ignore-whitespace ${PATCH}
    RESULT_VARIABLE already OUTPUT_QUIET ERROR_QUIET)
if (already EQUAL 0)
    return()
endif()
execute_process(COMMAND git apply --ignore-whitespace ${PATCH} RESULT_VARIABLE rc)
if (NOT rc EQUAL 0)
    message(FATAL_ERROR "Could not apply ${PATCH}")
endif()
