/* Native driver: dombench_native [file...]  (also runs synthetic 50KB/200KB/1MB). Prints one line per doc. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int32_t db_run(const char *html, uint32_t len, uint32_t reps, double *out);
char *db_make_html(uint32_t target, uint32_t *len_out);
char *db_make_wide(uint32_t n, uint32_t *len_out);
int32_t db_wide_select(const char *html, uint32_t len);
#include <time.h>
int main(int argc, char **argv) {
    if (getenv("DB_WIDE")) { uint32_t l; char *h = db_make_wide((uint32_t)atoi(getenv("DB_WIDE")), &l); struct timespec a, b; clock_gettime(CLOCK_MONOTONIC, &a); int r = db_wide_select(h, l); clock_gettime(CLOCK_MONOTONIC, &b); printf("WIDE n=%s result=%d ms=%.0f\n", getenv("DB_WIDE"), r, (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6); return 0; }
    static const char *names[] = { "synthetic 50KB", "synthetic 200KB", "synthetic 1MB" };
    static const uint32_t sizes[] = { 50u << 10, 200u << 10, 1u << 20 };
    for (int i = 0; i < 3 + argc - 1; i++) {
        char *h; uint32_t len; const char *label;
        if (i < 3) { h = db_make_html(sizes[i], &len); label = names[i]; }
        else {
            FILE *f = fopen(argv[i - 2], "rb"); if (!f) continue;
            fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
            h = malloc((size_t)n + 1); if (fread(h, 1, (size_t)n, f) != (size_t)n) return 1; len = (uint32_t)n; fclose(f);
            label = strrchr(argv[i - 2], '/'); label = label ? label + 1 : argv[i - 2];
        }
        double out[15];
        int rc = db_run(h, len, len > (512u << 10) ? 5 : 9, out);
        printf("DOC %s %u rc=%d", label, len, rc);
        for (int k = 0; k < 15; k++) printf(" %.3f", out[k]);
        printf("\n");
        free(h);
    }
    return 0;
}
