#ifndef JL_MOD_SONIVOX_V4_EAS_VERSION_H
#define JL_MOD_SONIVOX_V4_EAS_VERSION_H

#define MAKE_LIB_VERSION(a,b,c,d) (((((((EAS_U32) (a) << 8) | (EAS_U32) (b)) << 8) | (EAS_U32) (c)) << 8) | (EAS_U32) (d))
#define LIB_VERSION MAKE_LIB_VERSION(4,0,1,0)

#endif // JL_MOD_SONIVOX_V4_EAS_VERSION_H
