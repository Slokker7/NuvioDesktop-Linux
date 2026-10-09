/* Test-only inclusion of the real pinned mpv mapper and its ownership caller.
 * CUDA/device operations are stubs; no production fault-injection hooks.
 * A failed plane has partially allocated state, which uninit must release.
 * Returning NULL from the real ra_hwdec_mapper_create prevents later mapping.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "video/out/hwdec/hwdec_cuda.c"
#include "video/out/gpu/hwdec.c"

static int fail_plane, calls, pushes, pops, live;
static CUresult CUDAAPI push(CUcontext c) { pushes++; return CUDA_SUCCESS; }
static CUresult CUDAAPI pop(CUcontext *c) { pops++; return CUDA_SUCCESS; }
static bool ext_init(struct ra_hwdec_mapper *m, const struct ra_format *f, int n)
{
    calls++;
    ((struct cuda_mapper_priv *)m->priv)->ext[n] = (void *)1;
    live++;
    return n != fail_plane;
}
static void ext_uninit(const struct ra_hwdec_mapper *m, int n)
{
    struct cuda_mapper_priv *p = m->priv;
    if (p->ext[n]) { live--; p->ext[n] = NULL; }
}
void mp_msg(struct mp_log *log, int lev, const char *format, ...) {}
int mp_msg_level(struct mp_log *log) { return 0; }
char *mp_imgfmt_to_name_buf(char *b, size_t s, int fmt) { return b; }
void mp_image_set_params(struct mp_image *im, const struct mp_image_params *p) { im->params = *p; }
void mp_image_unrefp(struct mp_image **im) { *im = NULL; }
bool ra_get_imgfmt_desc(struct ra *ra, int fmt, struct ra_imgfmt_desc *out)
{ *out = (struct ra_imgfmt_desc){ .num_planes = 3 }; return true; }
void ra_tex_free(struct ra *ra, struct ra_tex **tex) { *tex = NULL; }

int main(void)
{
    CudaFunctions cu = {.cuCtxPushCurrent = push, .cuCtxPopCurrent = pop};
    struct cuda_hw_priv priv = {.cu = &cu, .ext_init = ext_init, .ext_uninit = ext_uninit};
    const struct ra_hwdec_mapper_driver mapper_driver = {
        .priv_size = sizeof(struct cuda_mapper_priv), .init = mapper_init,
        .uninit = mapper_uninit, .unmap = mapper_unmap,
    };
    const struct ra_hwdec_driver driver = {.imgfmts = {IMGFMT_CUDA, 0}, .mapper = &mapper_driver};
    struct ra_ctx ctx = {0};
    struct ra_hwdec owner = {.priv = &priv, .driver = &driver, .ra_ctx = &ctx};
    struct mp_image_params params = {.imgfmt = IMGFMT_CUDA, .hw_subfmt = IMGFMT_420P};
    int failed = 0;
    for (fail_plane = -1; fail_plane < 3; fail_plane++) {
        calls = pushes = pops = live = 0;
        struct ra_hwdec_mapper *m = ra_hwdec_mapper_create(&owner, &params);
        bool ok = fail_plane < 0 ? m != NULL : m == NULL;
        bool rejected = m == NULL;
        failed |= !ok || calls != (fail_plane < 0 ? 3 : fail_plane + 1);
        ra_hwdec_mapper_free(&m);
        bool clean = live == 0 && pushes == pops && pushes == 2;
        failed |= !clean;
        printf("{\"failed_plane\":%d,\"mapper_rejected\":%s,\"init_calls\":%d,\"cleanup_ok\":%s,\"result\":\"%s\"}\n",
               fail_plane, rejected ? "true" : "false", calls, clean ? "true" : "false",
               ok && clean ? "PASS" : "FAIL");
    }
    return failed ? 1 : 0;
}
