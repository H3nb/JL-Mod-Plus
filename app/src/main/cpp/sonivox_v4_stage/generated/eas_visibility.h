/* Minimal equivalent of SONiVOX's generated export header for ndk-build. */
#ifndef JL_MOD_PLUS_SONIVOX_V4_EAS_VISIBILITY_H
#define JL_MOD_PLUS_SONIVOX_V4_EAS_VISIBILITY_H

#if defined(__GNUC__) || defined(__clang__)
#define EAS_PUBLIC __attribute__((visibility("default")))
#define EAS_PRIVATE __attribute__((visibility("hidden")))
#else
#define EAS_PUBLIC
#define EAS_PRIVATE
#endif

#endif // JL_MOD_PLUS_SONIVOX_V4_EAS_VISIBILITY_H
