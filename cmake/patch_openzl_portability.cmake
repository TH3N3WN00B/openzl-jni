# Portability fixes applied to the fetched OpenZL sources (FetchContent PATCH_COMMAND).
# Each patch is idempotent (skipped when its marker is already present) and fails the
# configure step loudly if upstream changed the code it targets, so a newer OpenZL
# release gets re-checked instead of silently built without the fix.

function(openzl_jni_patch_file file marker old new)
    set(path "${OPENZL_SOURCE_DIR}/${file}")
    file(READ "${path}" text)
    string(FIND "${text}" "${marker}" already)
    if(NOT already EQUAL -1)
        return()
    endif()
    string(FIND "${text}" "${old}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "openzl-jni portability patch: anchor not found in ${file}; "
                "upstream changed, re-check whether the fix is still needed")
    endif()
    string(REPLACE "${old}" "${new}" text "${text}")
    file(WRITE "${path}" "${text}")
endfunction()

# 1. Buffer alignment on libc++ (macOS x86_64, FreeBSD).
#    OpenZL's protobuf tooling hands std::string::data() to OpenZL as *numeric*
#    input/output buffers. libc++ stores short strings inline at an odd offset of the
#    string object, so OpenZL rejects them as misaligned ("Buffer provided is
#    incorrectly aligned for target type", or a null TypedRef -> "NonNullUniqueCPtr obj
#    is null"). libstdc++ and Apple arm64's libc++ layout happen to be aligned.
#    Reserving more than the inline capacity moves the bytes to the heap, whose
#    allocations are aligned for any numeric width OpenZL uses.
set(OPENZL_JNI_ALIGN_MARKER "openzl-jni: heap storage keeps data() aligned")

openzl_jni_patch_file(tools/protobuf/ProtoDeserializer.cpp "${OPENZL_JNI_ALIGN_MARKER}"
[[        bufs[n].resize(size);
]]
"        // ${OPENZL_JNI_ALIGN_MARKER} (libc++ inlines short strings unaligned).
        bufs[n].reserve(size > 64 ? size : 64);
        bufs[n].resize(size);
")

openzl_jni_patch_file(tools/protobuf/ProtoSerializer.cpp "${OPENZL_JNI_ALIGN_MARKER}"
[[        auto buf = std::make_unique<std::string>(writers[i]->move());
]]
"        auto buf = std::make_unique<std::string>(writers[i]->move());
        // ${OPENZL_JNI_ALIGN_MARKER} (libc++ inlines short strings unaligned).
        buf->reserve(buf->size() > 64 ? buf->size() : 64);
")

# 2. C11 _Static_assert reached from C++ (illumos/Solaris).
#    GCC on illumos also defines __STDC_VERSION__ when compiling C++, so headers that
#    pick `_Static_assert` from `__STDC_VERSION__ >= 201112L` alone emit C syntax into
#    C++ translation units ("expected constructor, destructor, or type conversion").
set(OPENZL_JNI_STATIC_ASSERT_MARKER "openzl-jni: C11 _Static_assert only when not C++")

openzl_jni_patch_file(include/openzl/zl_ctransform.h "${OPENZL_JNI_STATIC_ASSERT_MARKER}"
[[#if defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
_Static_assert(]]
"/* ${OPENZL_JNI_STATIC_ASSERT_MARKER} */
#if !defined(__cplusplus) && defined(__STDC_VERSION__) && (__STDC_VERSION__ >= 201112L)
_Static_assert(")

openzl_jni_patch_file(src/openzl/common/assertion.h "${OPENZL_JNI_STATIC_ASSERT_MARKER}"
[[#    if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
/* Use the C11 _Static_assert keyword directly]]
"/* ${OPENZL_JNI_STATIC_ASSERT_MARKER} */
#    if !defined(__cplusplus) && defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
/* Use the C11 _Static_assert keyword directly")
