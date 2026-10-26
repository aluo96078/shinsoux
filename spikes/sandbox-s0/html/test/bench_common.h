/* Shared by bench_dom.c (lexbor) and bench_js.c (QuickJS + pure-JS bootstrap parser). */
#ifndef BENCH_COMMON_H
#define BENCH_COMMON_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>

static double bench_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}
static double bench_rss_mb(void) {
    struct rusage ru;
    getrusage(RUSAGE_SELF, &ru);
#ifdef __APPLE__
    return ru.ru_maxrss / 1048576.0;
#else
    return ru.ru_maxrss / 1024.0;
#endif
}
static int bench_cmp_d(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }
static double bench_median(double *v, int n) { qsort(v, (size_t)n, sizeof *v, bench_cmp_d); return v[n / 2]; }

/* Manga-list style page: nested blocks, links, images, entities, CJK text. */
static char *bench_make_html(size_t target_bytes) {
    size_t cap = target_bytes + 4096;
    char *b = malloc(cap);
    size_t n = (size_t)snprintf(b, cap, "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>List</title></head><body><div id=\"wrap\"><ul class=\"mh-list\">");
    for (int i = 0; n + 600 < target_bytes; i++) {
        n += (size_t)snprintf(b + n, cap - n,
            "<li class=\"item n%d\"><a class=\"cover\" href=\"/manhua-title-%d/\" title=\"Title %d\"><img src=\"//img.test/c/%d.jpg\" alt=\"cover %d\"></a>"
            "<div class=\"info\"><h3 class=\"name\"><a href=\"/manhua-title-%d/\">Title %d \xe6\xa8\x99\xe9\xa1\x8c</a></h3>"
            "<p class=\"desc\">Some description text &amp; entities number %d lorem ipsum dolor sit amet</p>"
            "<span class=\"tag\">tag%d</span><span class=\"tag\">tag%d</span></div></li>\n",
            i, i, i, i, i, i, i, i, i % 7, i % 5);
    }
    n += (size_t)snprintf(b + n, cap - n, "</ul><div class=\"pager\"><a class=\"prev\" href=\"?p=1\">Prev</a><a class=\"next\" href=\"?p=3\">Next</a></div></div></body></html>");
    return b;
}

static const char *const BENCH_SELECTORS[] = {
    "a[href*='/manhua-']",
    "ul.mh-list > li:nth-child(2n+1) a",
    "li:has(img)",
    "a:contains(Next)",
    "span.tag",
};
#define BENCH_NSEL 5
#endif
