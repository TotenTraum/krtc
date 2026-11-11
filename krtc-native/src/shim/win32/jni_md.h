/* JNI machine-dependent types for Windows x64 (LLP64), used when building krtc.dll on another OS
 * (build-jni.sh win): the host JDK ships only its own OS's jni_md.h. JDKs before 22 take JNICALL from here
 * (newer jni.h defines it the same way): x64 has one calling convention, so it is empty. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif
