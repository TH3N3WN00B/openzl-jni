#pragma once

#include <jni.h>
#include <cstddef>
#include <memory>
#include <string>
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "openzl/zl_compressor.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"

struct CachedJNIRefs {
    jfieldID nativeHandleField = nullptr;
    jclass nullPointerException = nullptr;
    jclass illegalArgumentException = nullptr;
    jclass illegalStateException = nullptr;
    jclass outOfMemoryError = nullptr;
};

struct NativeState {
    struct ScratchBuffer {
        std::unique_ptr<uint8_t[]> data;
        size_t capacity = 0;
        size_t size = 0;

        uint8_t* ensure(size_t required);
        void reset();
        void setSize(size_t newSize);
        uint8_t* ptr();
        const uint8_t* ptr() const;
    };

    openzl::Compressor compressor;
    ZL_CCtx* cctx = nullptr;
    ZL_DCtx* dctx = nullptr;
    ZL_GraphID startingGraph{ ZL_GRAPH_ZSTD };
    ScratchBuffer outputScratch;

    explicit NativeState(ZL_GraphID graph);
    ~NativeState();

    NativeState(const NativeState&) = delete;
    NativeState& operator=(const NativeState&) = delete;

    void reset();
    void setGraph(ZL_GraphID graph);
    // Prepares a pooled state for a new OpenZLCompressor: fresh Compressor, so no
    // parameters or registered graphs leak from the previous owner.
    void recycle(ZL_GraphID graph);
    // Replaces the Compressor with a fully built one (e.g. deserialized) and starts
    // from its own starting graph. Throws std::runtime_error if it has none.
    void loadCompressor(openzl::Compressor&& loaded);

private:
    static void expectSuccess(ZL_Report report, const char* action);
    void applyDefaultParameters();
    void configureGraph();
};

CachedJNIRefs& JniRefs();

bool initJniRefs(JNIEnv* env);
void clearJniRefs(JNIEnv* env);

NativeState* acquireState(ZL_GraphID graph);
void recycleState(NativeState* state);

NativeState* getState(JNIEnv* env, jobject obj);
void setNativeHandle(JNIEnv* env, jobject obj, NativeState* value);

bool ensureState(NativeState* state, const char* method);
bool checkArrayRange(JNIEnv* env, jbyteArray array, jint offset, jint length, const char* name);
bool ensureDirect(JNIEnv* env, jobject buffer, const char* name);
bool ensureDirectRange(JNIEnv* env,
        jobject buffer,
        jint position,
        jint length,
        const char* name);

void throwNew(JNIEnv* env, jclass clazz, const char* message);
void throwIllegalState(JNIEnv* env, const std::string& message);
void throwIllegalArgument(JNIEnv* env, const std::string& message);

// OpenZL resolves the parameters of a compression as: CCtx value, else the
// Compressor value, else the library default (a parameter counts as unset when 0).
// Defaults therefore belong on the Compressor; setting them on the CCtx would
// silently override OpenZLCompressor.setParameter() and deserialized compressors.
// The format version must end up non-zero, otherwise compression fails.
inline void applyCompressorDefaults(openzl::Compressor& compressor)
{
    if (compressor.getParameter(openzl::CParam::CompressionLevel) == 0) {
        compressor.setParameter(openzl::CParam::CompressionLevel, ZL_COMPRESSIONLEVEL_DEFAULT);
    }
    if (compressor.getParameter(openzl::CParam::FormatVersion) == 0) {
        compressor.setParameter(openzl::CParam::FormatVersion,
                static_cast<int>(ZL_getDefaultEncodingVersion()));
    }
}

// OpenZL >= 0.3.0 refuses to train a compressor whose format version is unset.
// Mirror the upstream CLI and default to the newest format the library writes.
inline void ensureTrainingFormatVersion(openzl::Compressor& compressor)
{
    if (compressor.getParameter(openzl::CParam::FormatVersion) == 0) {
        compressor.setParameter(openzl::CParam::FormatVersion, ZL_MAX_FORMAT_VERSION);
    }
}

ZL_GraphID graphIdFromOrdinal(jint ordinal);
jint graphOrdinalFromId(ZL_GraphID graph);
bool graphEquals(ZL_GraphID lhs, ZL_GraphID rhs);
