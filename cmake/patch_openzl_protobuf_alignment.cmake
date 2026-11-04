# OpenZL's protobuf tooling hands std::string::data() to OpenZL as *numeric*
# input/output buffers. With libc++ (macOS x86_64, FreeBSD) short strings are
# stored inline at an odd offset of the string object, so OpenZL rejects them as
# misaligned ("Buffer provided is incorrectly aligned for target type", or a null
# TypedRef -> "NonNullUniqueCPtr obj is null"). libstdc++ and Apple arm64's libc++
# layout happen to be aligned, which is why Linux/Windows/macOS arm64 work.
#
# Reserving more than the inline capacity moves the bytes to the heap, whose
# allocations are aligned for any numeric width OpenZL uses.
set(OPENZL_JNI_PATCH_MARKER "openzl-jni: heap storage keeps data() aligned")

function(openzl_jni_patch_file file old new)
    set(path "${OPENZL_SOURCE_DIR}/${file}")
    file(READ "${path}" text)
    string(FIND "${text}" "${OPENZL_JNI_PATCH_MARKER}" already)
    if(NOT already EQUAL -1)
        return()
    endif()
    string(FIND "${text}" "${old}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "openzl-jni alignment patch: anchor not found in ${file}; "
                "upstream changed, re-check whether the fix is still needed")
    endif()
    string(REPLACE "${old}" "${new}" text "${text}")
    file(WRITE "${path}" "${text}")
endfunction()

openzl_jni_patch_file(tools/protobuf/ProtoDeserializer.cpp
[[        bufs[n].resize(size);
]]
"        // ${OPENZL_JNI_PATCH_MARKER} (libc++ inlines short strings unaligned).
        bufs[n].reserve(size > 64 ? size : 64);
        bufs[n].resize(size);
")

openzl_jni_patch_file(tools/protobuf/ProtoSerializer.cpp
[[        auto buf = std::make_unique<std::string>(writers[i]->move());
]]
"        auto buf = std::make_unique<std::string>(writers[i]->move());
        // ${OPENZL_JNI_PATCH_MARKER} (libc++ inlines short strings unaligned).
        buf->reserve(buf->size() > 64 ? buf->size() : 64);
")
