/* The few helpers RNNoise 0.2's vec.h and vec_neon.h take from Opus's os_support.h, which its release archive
 * leaves out. Only on the include path while compiling RNNoise (build-shim.sh). */
#ifndef KRTC_RNNOISE_OS_SUPPORT_H
#define KRTC_RNNOISE_OS_SUPPORT_H

#include <string.h>
#include "opus_types.h"

#ifndef OPUS_INLINE
#define OPUS_INLINE inline
#endif

/* Copy, move or clear n elements of dst; the 0* term checks the types at compile time. */
#define OPUS_COPY(dst, src, n) (memcpy((dst), (src), (n) * sizeof(*(dst)) + 0 * ((dst) - (src))))
#define OPUS_MOVE(dst, src, n) (memmove((dst), (src), (n) * sizeof(*(dst)) + 0 * ((dst) - (src))))
#define OPUS_CLEAR(dst, n) (memset((dst), 0, (n) * sizeof(*(dst))))

#endif
