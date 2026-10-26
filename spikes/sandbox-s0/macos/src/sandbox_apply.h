#ifndef SANDBOX_APPLY_H
#define SANDBOX_APPLY_H
/* Applies the deny-all Seatbelt profile to the current process (irreversible). Returns 0 on success. */
int apply_seatbelt_sandbox(char *errbuf, unsigned errlen);
#endif
