#include "receiver.hpp"
#include <jni.h>
#include <memory>
#include <stdexcept>

namespace {
struct Native {
    JNIEnv* env=nullptr;
    jobject owner=nullptr;
    jmethodID on_frame=nullptr;
    rover::Receiver receiver;
    Native(JNIEnv* e,jobject object,int session): receiver([this](const auto& au,int64_t pts,bool key) {
        if(env->ExceptionCheck()) return;
        auto bytes=env->NewByteArray(au.size()); if(!bytes) return;
        env->SetByteArrayRegion(bytes,0,au.size(),reinterpret_cast<const jbyte*>(au.data()));
        env->CallVoidMethod(owner,on_frame,bytes,jlong(pts),jboolean(key)); env->DeleteLocalRef(bytes);
    },session) {
        auto type=e->GetObjectClass(object); on_frame=e->GetMethodID(type,"onAccessUnit","([BJZ)V"); e->DeleteLocalRef(type);
    }
};
Native* get(jlong p) { return reinterpret_cast<Native*>(p); }
void error(JNIEnv* e,const char* what) { auto c=e->FindClass("java/lang/IllegalStateException"); e->ThrowNew(c,what); e->DeleteLocalRef(c); }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_flyingcomputer_roverground_VideoReceiver_nativeCreate(JNIEnv* e,jobject o,jint session) {
    try { return reinterpret_cast<jlong>(new Native(e,o,session)); } catch(const std::exception& ex) { error(e,ex.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_flyingcomputer_roverground_VideoReceiver_nativeDestroy(JNIEnv*,jobject,jlong p) { delete get(p); }
extern "C" JNIEXPORT void JNICALL Java_com_flyingcomputer_roverground_VideoReceiver_nativeFeed(JNIEnv* e,jobject o,jlong p,jbyteArray bytes) {
    auto* n=get(p); if(!n) return;
    try {
        auto len=e->GetArrayLength(bytes); std::vector<uint8_t> copy(len);
        e->GetByteArrayRegion(bytes,0,len,reinterpret_cast<jbyte*>(copy.data()));
        n->env=e; n->owner=o; n->receiver.ingest(copy.data(),copy.size()); n->owner=nullptr; n->env=nullptr;
    } catch(const std::exception& ex) { error(e,ex.what()); }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_flyingcomputer_roverground_VideoReceiver_nativeTick(JNIEnv* e,jobject o,jlong p,jboolean feedback) {
    auto* n=get(p); if(!n) return nullptr;
    try {
        n->env=e; n->owner=o; n->receiver.tick(); n->owner=nullptr; n->env=nullptr;
        if(!feedback) return nullptr;
        auto bytes=n->receiver.feedback(); auto out=e->NewByteArray(bytes.size());
        if(out) e->SetByteArrayRegion(out,0,bytes.size(),reinterpret_cast<const jbyte*>(bytes.data())); return out;
    } catch(const std::exception& ex) { error(e,ex.what()); return nullptr; }
}
