package dev.shinsou.kmp.plugin

/**
 * Browser-side ISO-BMFF validation run before AVIF decode allocation. This parses box boundaries;
 * it never searches arbitrary payload bytes for an `ispe` marker.
 *
 * The returned function accepts a Uint8Array and returns `{width,height}`, or throws on malformed,
 * missing, conflicting, or excessive spatial extents.
 */
internal fun reviewedAvifDimensionParserScript(): String = """
    (bytes => {
      const MAX_DIMENSION = 8192, MAX_PIXELS = $REVIEWED_BROWSER_IMAGE_MAX_PIXELS, MAX_DEPTH = 8, MAX_BOXES = 4096;
      if (!(bytes instanceof Uint8Array) || bytes.byteLength < 16) throw new Error('avif_metadata');
      const text = offset => String.fromCharCode(bytes[offset], bytes[offset+1], bytes[offset+2], bytes[offset+3]);
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      const u32 = offset => view.getUint32(offset, false);
      const safe64 = offset => {
        const high = u32(offset), low = u32(offset + 4);
        if (high > 0x1fffff) throw new Error('avif_metadata');
        return high * 4294967296 + low;
      };
      let boxes = 0, compatible = false, extent = null;
      const walk = (start, end, depth, context) => {
        if (depth > MAX_DEPTH || start < 0 || end > bytes.byteLength || start > end) throw new Error('avif_metadata');
        let offset = start;
        while (offset < end) {
          if (++boxes > MAX_BOXES || end - offset < 8) throw new Error('avif_metadata');
          let size = u32(offset), header = 8;
          const type = text(offset + 4);
          if (size === 1) { if (end - offset < 16) throw new Error('avif_metadata'); size = safe64(offset + 8); header = 16; }
          else if (size === 0) size = end - offset;
          if (size < header || size > end - offset) throw new Error('avif_metadata');
          const body = offset + header, limit = offset + size;
          if (context === 'root' && type === 'ftyp') {
            if (size < header + 8 || ((limit - body) & 3) !== 0) throw new Error('avif_metadata');
            if (text(body) === 'avif' || text(body) === 'avis') compatible = true;
            for (let p = body + 8; p + 4 <= limit; p += 4) if (text(p) === 'avif' || text(p) === 'avis') compatible = true;
          } else if (context === 'root' && type === 'meta') {
            if (size < header + 4) throw new Error('avif_metadata');
            walk(body + 4, limit, depth + 1, 'meta');
          } else if (context === 'meta' && type === 'iprp') {
            walk(body, limit, depth + 1, 'iprp');
          } else if (context === 'iprp' && type === 'ipco') {
            walk(body, limit, depth + 1, 'ipco');
          } else if (context === 'ipco' && type === 'ispe') {
            if (size !== header + 12 || u32(body) !== 0) throw new Error('avif_metadata');
            const width = u32(body + 4), height = u32(body + 8);
            if (!width || !height || width > MAX_DIMENSION || height > MAX_DIMENSION || width * height > MAX_PIXELS) throw new Error('avif_metadata');
            if (extent && (extent.width !== width || extent.height !== height)) throw new Error('avif_metadata');
            extent = {width, height};
          }
          offset = limit;
        }
      };
      walk(0, bytes.byteLength, 0, 'root');
      if (!compatible || !extent) throw new Error('avif_metadata');
      return extent;
    })
""".trimIndent()
