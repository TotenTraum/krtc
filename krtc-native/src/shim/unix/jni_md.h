/* JNI machine-dependent types for Linux (LP64), used when building libkrtc_jni.so in Docker (build-jni.sh linux):
 * the JDK mounted from the host ships only its own OS's jni_md.h. Same definitions as the JDK's Linux one. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
typedef long jlong;
typedef signed char jbyte;

#endif
