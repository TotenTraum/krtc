/* JNI machine-dependent types for Windows x64 (LLP64), used when building krtc.dll on another OS
 * (build-jni.sh win): the host JDK ships only its own OS's jni_md.h. jni.h defines JNICALL (calling conventions
 * do not differ on x64). */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif
