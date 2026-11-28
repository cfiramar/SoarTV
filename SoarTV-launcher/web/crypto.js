/*
 * Pure synchronous ES5 cryptographic and encoding utilities for LG webOS 3-4 and Samsung Tizen 2.3-4.
 *
 * Why: Older smart TV WebKit/Chromium engines lack SubtleCrypto on file:// origins and cannot use
 * modern async crypto APIs. Trakt PKCE (S256), Tuya OpenAPI v2.0 (HMAC-SHA256), and SoarTV v2
 * Zero-Knowledge Cloud Vault (PBKDF2-HMAC-SHA256 120,000 iterations + AES-256-GCM) require
 * RFC/NIST-compliant synchronous ES5 implementations that match Android TV (Java JCA) byte-for-byte.
 */
var SoarTvCrypto = (function () {
  'use strict';

  var K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  var B64_URL = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  var B64_STD = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  var DEFAULT_VAULT_ID = 'primary';
  var HOUSEHOLD_VAULT_ID = 'household';
  var PROFILE_VAULT_PREFIX = 'profile:';
  var HOUSEHOLD_VAULT_KDF_DOMAIN = 'soartv-vault-key-v2:';
  var KDF_ALGORITHM_LABEL = 'PBKDF2-HMAC-SHA256';
  var DEFAULT_KDF_ITERATIONS = 120000;
  var MIN_KDF_ITERATIONS = 100000;

  // NIST FIPS 197 AES S-Box
  var AES_SBOX = [
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
  ];
  var AES_RCON = [0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];

  /** Encodes a JavaScript string into an array of UTF-8 byte values (0..255). */
  function utf8Bytes(str) {
    var s = String(str === undefined || str === null ? '' : str);
    var out = [], i, c, c2, cp;
    for (i = 0; i < s.length; i++) {
      c = s.charCodeAt(i);
      if (c < 0x80) {
        out.push(c);
      } else if (c < 0x800) {
        out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        c2 = s.charCodeAt(++i);
        cp = 0x10000 + (((c & 0x3ff) << 10) | (c2 & 0x3ff));
        out.push(
          0xf0 | (cp >> 18),
          0x80 | ((cp >> 12) & 0x3f),
          0x80 | ((cp >> 6) & 0x3f),
          0x80 | (cp & 0x3f)
        );
      } else {
        out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
      }
    }
    return out;
  }

  /** Decodes a UTF-8 byte array (0..255) back into a JavaScript string. */
  function utf8Decode(bytes) {
    var out = '', i = 0, len = bytes.length, b0, b1, b2, b3, cp;
    while (i < len) {
      b0 = bytes[i++] & 0xff;
      if (b0 < 0x80) {
        out += String.fromCharCode(b0);
      } else if ((b0 & 0xe0) === 0xc0 && i < len) {
        b1 = bytes[i++] & 0x3f;
        out += String.fromCharCode(((b0 & 0x1f) << 6) | b1);
      } else if ((b0 & 0xf0) === 0xe0 && i + 1 < len) {
        b1 = bytes[i++] & 0x3f;
        b2 = bytes[i++] & 0x3f;
        out += String.fromCharCode(((b0 & 0x0f) << 12) | (b1 << 6) | b2);
      } else if ((b0 & 0xf8) === 0xf0 && i + 2 < len) {
        b1 = bytes[i++] & 0x3f;
        b2 = bytes[i++] & 0x3f;
        b3 = bytes[i++] & 0x3f;
        cp = (((b0 & 0x07) << 18) | (b1 << 12) | (b2 << 6) | b3) - 0x10000;
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
      } else {
        out += '\ufffd';
      }
    }
    return out;
  }

  function rotr(x, n) {
    return (x >>> n) | (x << (32 - n));
  }

  /** Compresses a single 64-byte block at `offset` into 8-word SHA-256 state `st` using scratch `w`. */
  function sha256Compress(st, buf, offset, w) {
    var i, a, b, c, d, e, f, g, h, s0, s1, ch, maj, t1, t2;
    for (i = 0; i < 16; i++) {
      w[i] = ((buf[offset + i * 4] << 24) |
              (buf[offset + i * 4 + 1] << 16) |
              (buf[offset + i * 4 + 2] << 8) |
              buf[offset + i * 4 + 3]) >>> 0;
    }
    for (i = 16; i < 64; i++) {
      s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) >>> 0;
      s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    a = st[0]; b = st[1]; c = st[2]; d = st[3];
    e = st[4]; f = st[5]; g = st[6]; h = st[7];
    for (i = 0; i < 64; i++) {
      s1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
      ch = ((e & f) ^ ((~e) & g)) >>> 0;
      t1 = (h + s1 + ch + K[i] + w[i]) >>> 0;
      s0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
      maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      t2 = (s0 + maj) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0;
      d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    st[0] = (st[0] + a) >>> 0; st[1] = (st[1] + b) >>> 0; st[2] = (st[2] + c) >>> 0; st[3] = (st[3] + d) >>> 0;
    st[4] = (st[4] + e) >>> 0; st[5] = (st[5] + f) >>> 0; st[6] = (st[6] + g) >>> 0; st[7] = (st[7] + h) >>> 0;
  }

  /** Computes SHA-256 digest of a byte array (0..255) and returns a 32-byte array. */
  function sha256BytesFromArray(input) {
    var msg = input.slice(0);
    var bitLen = msg.length * 8;
    msg.push(0x80);
    while ((msg.length % 64) !== 56) msg.push(0);

    var highBits = Math.floor(bitLen / 4294967296);
    var lowBits = bitLen >>> 0;
    msg.push(
      (highBits >>> 24) & 0xff, (highBits >>> 16) & 0xff, (highBits >>> 8) & 0xff, highBits & 0xff,
      (lowBits >>> 24) & 0xff, (lowBits >>> 16) & 0xff, (lowBits >>> 8) & 0xff, lowBits & 0xff
    );

    var st = [
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ];
    var w = new Array(64), offset, i;
    for (offset = 0; offset < msg.length; offset += 64) {
      sha256Compress(st, msg, offset, w);
    }

    var digest = new Array(32);
    for (i = 0; i < 8; i++) {
      digest[i * 4] = (st[i] >>> 24) & 0xff;
      digest[i * 4 + 1] = (st[i] >>> 16) & 0xff;
      digest[i * 4 + 2] = (st[i] >>> 8) & 0xff;
      digest[i * 4 + 3] = st[i] & 0xff;
    }
    return digest;
  }

  function bytesToHex(bytes, upper) {
    var hex = '', i, b, s;
    for (i = 0; i < bytes.length; i++) {
      b = bytes[i] & 0xff;
      s = b.toString(16);
      if (s.length < 2) s = '0' + s;
      hex += s;
    }
    return upper ? hex.toUpperCase() : hex;
  }

  /** Computes SHA-256 digest of a UTF-8 string as a 32-byte array. */
  function sha256Bytes(str) {
    return sha256BytesFromArray(utf8Bytes(str));
  }

  /** Computes lowercase SHA-256 hex digest of a UTF-8 string. */
  function sha256Hex(str) {
    return bytesToHex(sha256Bytes(str), false);
  }

  /** Computes raw 32-byte HMAC-SHA256 from byte arrays. */
  function hmacSha256Bytes(keyBytesInput, msgBytes) {
    var keyBytes = keyBytesInput.slice(0);
    if (keyBytes.length > 64) keyBytes = sha256BytesFromArray(keyBytes);
    var oKey = new Array(64), iKey = new Array(64), i, kb;
    for (i = 0; i < 64; i++) {
      kb = i < keyBytes.length ? (keyBytes[i] & 0xff) : 0;
      oKey[i] = kb ^ 0x5c;
      iKey[i] = kb ^ 0x36;
    }
    var inner = sha256BytesFromArray(iKey.concat(msgBytes));
    return sha256BytesFromArray(oKey.concat(inner));
  }

  /** Computes HMAC-SHA256 hex digest of `message` with `secret` (uppercase when `upper` is true). */
  function hmacSha256Hex(message, secret, upper) {
    return bytesToHex(hmacSha256Bytes(utf8Bytes(secret), utf8Bytes(message)), !!upper);
  }

  /**
   * RFC 2898 PBKDF2-HMAC-SHA256 using pre-compressed HMAC key states for fast synchronous ES5 execution
   * (cuts 120,000-iteration SHA-256 block compressions in half with zero per-iteration allocations).
   */
  function pbkdf2HmacSha256(passwordInput, saltBytes, iterations, dkLen) {
    var passBytes = typeof passwordInput === 'string' ? utf8Bytes(passwordInput) : passwordInput.slice(0);
    if (passBytes.length > 64) passBytes = sha256BytesFromArray(passBytes);
    var iKey = new Array(64), oKey = new Array(64), i, kb;
    for (i = 0; i < 64; i++) {
      kb = i < passBytes.length ? (passBytes[i] & 0xff) : 0;
      iKey[i] = kb ^ 0x36;
      oKey[i] = kb ^ 0x5c;
    }
    var w = new Array(64);
    var initH = [
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ];
    var ipadState = initH.slice(0);
    var opadState = initH.slice(0);
    sha256Compress(ipadState, iKey, 0, w);
    sha256Compress(opadState, oKey, 0, w);

    // Pre-allocated single 64-byte padded block for 32-byte intermediate digests (64 + 32 = 96 bytes -> 768 bits = 0x300)
    var block32 = new Array(64);
    for (i = 32; i < 64; i++) block32[i] = 0;
    block32[32] = 0x80;
    block32[62] = 0x03;
    block32[63] = 0x00;

    var curState = new Array(8);
    var blocksNeeded = Math.ceil(dkLen / 32);
    var derived = [];
    var blockIdx, iter, j;

    for (blockIdx = 1; blockIdx <= blocksNeeded; blockIdx++) {
      // U_1 = HMAC(pass, salt || INT_32_BE(blockIdx))
      var saltBlock = saltBytes.slice(0);
      saltBlock.push(
        (blockIdx >>> 24) & 0xff,
        (blockIdx >>> 16) & 0xff,
        (blockIdx >>> 8) & 0xff,
        blockIdx & 0xff
      );
      var uBytes = hmacSha256Bytes(passBytes, saltBlock);
      var tWords = new Array(8);
      var uWords = new Array(8);
      for (j = 0; j < 8; j++) {
        uWords[j] = ((uBytes[j * 4] << 24) | (uBytes[j * 4 + 1] << 16) |
                     (uBytes[j * 4 + 2] << 8) | uBytes[j * 4 + 3]) >>> 0;
        tWords[j] = uWords[j];
      }

      for (iter = 1; iter < iterations; iter++) {
        for (j = 0; j < 8; j++) {
          block32[j * 4] = (uWords[j] >>> 24) & 0xff;
          block32[j * 4 + 1] = (uWords[j] >>> 16) & 0xff;
          block32[j * 4 + 2] = (uWords[j] >>> 8) & 0xff;
          block32[j * 4 + 3] = uWords[j] & 0xff;
          curState[j] = ipadState[j];
        }
        sha256Compress(curState, block32, 0, w);

        for (j = 0; j < 8; j++) {
          block32[j * 4] = (curState[j] >>> 24) & 0xff;
          block32[j * 4 + 1] = (curState[j] >>> 16) & 0xff;
          block32[j * 4 + 2] = (curState[j] >>> 8) & 0xff;
          block32[j * 4 + 3] = curState[j] & 0xff;
          curState[j] = opadState[j];
        }
        sha256Compress(curState, block32, 0, w);

        for (j = 0; j < 8; j++) {
          uWords[j] = curState[j];
          tWords[j] = (tWords[j] ^ uWords[j]) >>> 0;
        }
      }

      for (j = 0; j < 8 && derived.length < dkLen; j++) {
        derived.push((tWords[j] >>> 24) & 0xff);
        if (derived.length < dkLen) derived.push((tWords[j] >>> 16) & 0xff);
        if (derived.length < dkLen) derived.push((tWords[j] >>> 8) & 0xff);
        if (derived.length < dkLen) derived.push(tWords[j] & 0xff);
      }
    }
    return derived;
  }

  /** Encodes a byte array into unpadded URL-safe Base64 (RFC 4648 Section 5). */
  function base64Url(bytes) {
    var out = '', i, n, b0, b1, b2;
    for (i = 0; i < bytes.length; i += 3) {
      b0 = bytes[i] & 0xff;
      b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xff : 0;
      b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xff : 0;
      n = (b0 << 16) | (b1 << 8) | b2;
      out += B64_URL.charAt((n >>> 18) & 63) + B64_URL.charAt((n >>> 12) & 63);
      if (i + 1 < bytes.length) out += B64_URL.charAt((n >>> 6) & 63);
      if (i + 2 < bytes.length) out += B64_URL.charAt(n & 63);
    }
    return out;
  }

  /** Encodes a byte array into standard padded Base64 (RFC 4648 Section 4). */
  function base64Encode(bytes) {
    var out = '', i, n, b0, b1, b2;
    for (i = 0; i < bytes.length; i += 3) {
      b0 = bytes[i] & 0xff;
      b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xff : 0;
      b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xff : 0;
      n = (b0 << 16) | (b1 << 8) | b2;
      out += B64_STD.charAt((n >>> 18) & 63) + B64_STD.charAt((n >>> 12) & 63);
      out += (i + 1 < bytes.length) ? B64_STD.charAt((n >>> 6) & 63) : '=';
      out += (i + 2 < bytes.length) ? B64_STD.charAt(n & 63) : '=';
    }
    return out;
  }

  /** Decodes a standard or URL-safe Base64 string into an array of byte values (0..255). */
  function base64Decode(b64) {
    var clean = String(b64 || '').replace(/[\r\n\s]/g, '').replace(/-/g, '+').replace(/_/g, '/');
    while (clean.length % 4 !== 0) clean += '=';
    var out = [], i, c0, c1, c2, c3, n;
    for (i = 0; i < clean.length; i += 4) {
      c0 = B64_STD.indexOf(clean.charAt(i));
      c1 = B64_STD.indexOf(clean.charAt(i + 1));
      c2 = clean.charAt(i + 2) === '=' ? -1 : B64_STD.indexOf(clean.charAt(i + 2));
      c3 = clean.charAt(i + 3) === '=' ? -1 : B64_STD.indexOf(clean.charAt(i + 3));
      if (c0 < 0 || c1 < 0) throw new Error('Invalid Base64 input');
      n = (c0 << 18) | (c1 << 12) | ((c2 > 0 ? c2 : 0) << 6) | (c3 > 0 ? c3 : 0);
      out.push((n >>> 16) & 0xff);
      if (c2 >= 0) out.push((n >>> 8) & 0xff);
      if (c3 >= 0) out.push(n & 0xff);
    }
    return out;
  }

  // MARK: - Pure ES5 AES-256 + GHASH + AES-256-GCM (NIST FIPS 197 & SP 800-38D)

  function aes256ExpandKey(keyBytes) {
    if (!keyBytes || keyBytes.length !== 32) throw new Error('AES-256 requires a 32-byte key');
    var rk = new Array(240), i, rconIdx = 1, t0, t1, t2, t3;
    for (i = 0; i < 32; i++) rk[i] = keyBytes[i] & 0xff;
    for (i = 32; i < 240; i += 4) {
      t0 = rk[i - 4]; t1 = rk[i - 3]; t2 = rk[i - 2]; t3 = rk[i - 1];
      if ((i % 32) === 0) {
        var tmp = t0;
        t0 = AES_SBOX[t1] ^ AES_RCON[rconIdx++];
        t1 = AES_SBOX[t2];
        t2 = AES_SBOX[t3];
        t3 = AES_SBOX[tmp];
      } else if ((i % 32) === 16) {
        t0 = AES_SBOX[t0]; t1 = AES_SBOX[t1]; t2 = AES_SBOX[t2]; t3 = AES_SBOX[t3];
      }
      rk[i] = rk[i - 32] ^ t0;
      rk[i + 1] = rk[i - 31] ^ t1;
      rk[i + 2] = rk[i - 30] ^ t2;
      rk[i + 3] = rk[i - 29] ^ t3;
    }
    return rk;
  }

  function xtime(b) {
    return ((b << 1) ^ ((b & 0x80) ? 0x1b : 0)) & 0xff;
  }

  function aes256EncryptBlock(rk, input16) {
    var s = new Array(16), i, r, off, t0, t1, t2, t3, u, v;
    for (i = 0; i < 16; i++) s[i] = (input16[i] ^ rk[i]) & 0xff;
    for (r = 1; r <= 14; r++) {
      // SubBytes
      for (i = 0; i < 16; i++) s[i] = AES_SBOX[s[i]];
      // ShiftRows
      u = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = u;
      u = s[2]; v = s[6]; s[2] = s[10]; s[6] = s[14]; s[10] = u; s[14] = v;
      u = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = u;
      // MixColumns (rounds 1..13)
      if (r < 14) {
        for (i = 0; i < 16; i += 4) {
          t0 = s[i]; t1 = s[i + 1]; t2 = s[i + 2]; t3 = s[i + 3];
          u = t0 ^ t1 ^ t2 ^ t3;
          s[i] = t0 ^ u ^ xtime(t0 ^ t1);
          s[i + 1] = t1 ^ u ^ xtime(t1 ^ t2);
          s[i + 2] = t2 ^ u ^ xtime(t2 ^ t3);
          s[i + 3] = t3 ^ u ^ xtime(t3 ^ t0);
        }
      }
      // AddRoundKey
      off = r * 16;
      for (i = 0; i < 16; i++) s[i] = (s[i] ^ rk[off + i]) & 0xff;
    }
    return s;
  }

  /** Multiplies two 16-byte GF(2^128) elements per NIST SP 800-38D Algorithm 1. */
  function ghashMul(x, y) {
    var z = [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];
    var v = y.slice(0), i, j, bit, lsb, carry, nextCarry;
    for (i = 0; i < 128; i++) {
      bit = (x[i >> 3] >>> (7 - (i & 7))) & 1;
      if (bit) {
        for (j = 0; j < 16; j++) z[j] ^= v[j];
      }
      lsb = v[15] & 1;
      carry = 0;
      for (j = 0; j < 16; j++) {
        nextCarry = v[j] & 1;
        v[j] = (v[j] >>> 1) | (carry << 7);
        carry = nextCarry;
      }
      if (lsb) v[0] ^= 0xe1;
    }
    return z;
  }

  function ghashUpdateBlocks(h, y, data) {
    var offset, i, blockLen;
    for (offset = 0; offset < data.length; offset += 16) {
      blockLen = Math.min(16, data.length - offset);
      for (i = 0; i < blockLen; i++) y[i] ^= data[offset + i] & 0xff;
      y = ghashMul(y, h);
    }
    return y;
  }

  function ghashCompute(h, aadBytes, cipherBytes) {
    var y = [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];
    if (aadBytes && aadBytes.length) y = ghashUpdateBlocks(h, y, aadBytes);
    if (cipherBytes && cipherBytes.length) y = ghashUpdateBlocks(h, y, cipherBytes);
    var aadBits = (aadBytes ? aadBytes.length : 0) * 8;
    var ctBits = (cipherBytes ? cipherBytes.length : 0) * 8;
    var aadHi = Math.floor(aadBits / 4294967296), aadLo = aadBits >>> 0;
    var ctHi = Math.floor(ctBits / 4294967296), ctLo = ctBits >>> 0;
    var lenBlock = [
      (aadHi >>> 24) & 0xff, (aadHi >>> 16) & 0xff, (aadHi >>> 8) & 0xff, aadHi & 0xff,
      (aadLo >>> 24) & 0xff, (aadLo >>> 16) & 0xff, (aadLo >>> 8) & 0xff, aadLo & 0xff,
      (ctHi >>> 24) & 0xff, (ctHi >>> 16) & 0xff, (ctHi >>> 8) & 0xff, ctHi & 0xff,
      (ctLo >>> 24) & 0xff, (ctLo >>> 16) & 0xff, (ctLo >>> 8) & 0xff, ctLo & 0xff
    ];
    for (var i = 0; i < 16; i++) y[i] ^= lenBlock[i];
    return ghashMul(y, h);
  }

  function inc32(counter) {
    var c = ((counter[12] << 24) | (counter[13] << 16) | (counter[14] << 8) | counter[15]) >>> 0;
    c = (c + 1) >>> 0;
    counter[12] = (c >>> 24) & 0xff;
    counter[13] = (c >>> 16) & 0xff;
    counter[14] = (c >>> 8) & 0xff;
    counter[15] = c & 0xff;
  }

  /** Encrypts `plaintextBytes` with AES-256-GCM (12-byte IV, optional AAD) and returns ciphertext || 16-byte tag. */
  function aes256GcmEncrypt(keyBytes, iv12, plaintextBytes, aadBytes) {
    if (!iv12 || iv12.length !== 12) throw new Error('AES-GCM requires a 12-byte IV');
    var rk = aes256ExpandKey(keyBytes);
    var zeroBlock = [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];
    var h = aes256EncryptBlock(rk, zeroBlock);
    var j0 = iv12.slice(0).concat([0, 0, 0, 1]);
    var ctr = j0.slice(0);
    var ct = new Array(plaintextBytes.length), offset, i, ks;
    for (offset = 0; offset < plaintextBytes.length; offset += 16) {
      inc32(ctr);
      ks = aes256EncryptBlock(rk, ctr);
      for (i = 0; i < 16 && offset + i < plaintextBytes.length; i++) {
        ct[offset + i] = (plaintextBytes[offset + i] ^ ks[i]) & 0xff;
      }
    }
    var s = ghashCompute(h, aadBytes || [], ct);
    var ej0 = aes256EncryptBlock(rk, j0);
    var tag = new Array(16);
    for (i = 0; i < 16; i++) tag[i] = (s[i] ^ ej0[i]) & 0xff;
    return ct.concat(tag);
  }

  /** Authenticates and decrypts `cipherWithTag` (ciphertext || 16-byte tag) with AES-256-GCM. */
  function aes256GcmDecrypt(keyBytes, iv12, cipherWithTag, aadBytes) {
    if (!iv12 || iv12.length !== 12) throw new Error('AES-GCM requires a 12-byte IV');
    if (!cipherWithTag || cipherWithTag.length < 16) throw new Error('Ciphertext too short');
    var rk = aes256ExpandKey(keyBytes);
    var zeroBlock = [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];
    var h = aes256EncryptBlock(rk, zeroBlock);
    var j0 = iv12.slice(0).concat([0, 0, 0, 1]);
    var ctLen = cipherWithTag.length - 16;
    var ct = cipherWithTag.slice(0, ctLen);
    var givenTag = cipherWithTag.slice(ctLen);
    var s = ghashCompute(h, aadBytes || [], ct);
    var ej0 = aes256EncryptBlock(rk, j0);
    var diff = 0, i;
    for (i = 0; i < 16; i++) diff |= ((s[i] ^ ej0[i]) & 0xff) ^ (givenTag[i] & 0xff);
    if (diff !== 0) throw new Error('Vault authentication tag mismatch');

    var ctr = j0.slice(0);
    var pt = new Array(ctLen), offset, ks;
    for (offset = 0; offset < ctLen; offset += 16) {
      inc32(ctr);
      ks = aes256EncryptBlock(rk, ctr);
      for (i = 0; i < 16 && offset + i < ctLen; i++) {
        pt[offset + i] = (ct[offset + i] ^ ks[i]) & 0xff;
      }
    }
    return pt;
  }

  // MARK: - SoarTV v2 Automatic Household-Key Multi-Slot Zero-Knowledge Vault

  /** Derives the domain-separated E2EE vault passphrase identically to Java CloudVaultCrypto. */
  function deriveHouseholdVaultPassphrase(householdKey) {
    var clean = String(householdKey || '').replace(/^\s+|\s+$/g, '');
    if (!clean) throw new Error('householdKey must not be empty');
    return sha256Hex(HOUSEHOLD_VAULT_KDF_DOMAIN + clean);
  }

  /** Builds the canonical per-profile vault slot ID ("profile:<uuid>"). */
  function profileVaultId(profileId) {
    var clean = String(profileId || '').replace(/^\s+|\s+$/g, '');
    if (!clean) throw new Error('profileId must not be empty');
    return PROFILE_VAULT_PREFIX + clean;
  }

  /** Encrypts a flat key-value secret map into a Supabase `encrypted_vault` row matching Java CloudVaultCrypto. */
  function encryptVaultMap(vaultId, secretsMap, passphrase, iterationsOverride) {
    var pass = String(passphrase || '');
    if (!pass) throw new Error('Vault passphrase must not be empty');
    var slot = String(vaultId || '').replace(/^\s+|\s+$/g, '') || DEFAULT_VAULT_ID;
    var iters = (typeof iterationsOverride === 'number' && iterationsOverride >= MIN_KDF_ITERATIONS)
      ? Math.floor(iterationsOverride)
      : DEFAULT_KDF_ITERATIONS;

    var clean = {}, count = 0, k;
    for (k in secretsMap) {
      if (Object.prototype.hasOwnProperty.call(secretsMap, k)) {
        var ck = String(k || '').replace(/^\s+|\s+$/g, '');
        if (ck && secretsMap[k] !== null && secretsMap[k] !== undefined) {
          clean[ck] = String(secretsMap[k]);
          count++;
        }
      }
    }
    var salt = randomBytes(16);
    var iv = randomBytes(12);
    var key = pbkdf2HmacSha256(pass, salt, iters, 32);
    var aad = utf8Bytes('soartv-vault-v1:' + KDF_ALGORITHM_LABEL + ':' + iters);
    var plaintext = utf8Bytes(JSON.stringify(clean));
    var cipherWithTag = aes256GcmEncrypt(key, iv, plaintext, aad);
    return {
      vault_id: slot,
      kdf_algorithm: KDF_ALGORITHM_LABEL,
      kdf_salt: base64Encode(salt),
      kdf_iterations: iters,
      cipher_iv: base64Encode(iv),
      ciphertext: base64Encode(cipherWithTag),
      key_count: count
    };
  }

  /** Decrypts a Supabase `encrypted_vault` row back into a flat key-value map of secret strings. */
  function decryptVaultMap(envelope, passphrase) {
    if (!envelope || typeof envelope !== 'object') throw new Error('Vault envelope missing');
    var pass = String(passphrase || '');
    if (!pass) throw new Error('Vault passphrase must not be empty');
    var algo = String(envelope.kdf_algorithm || envelope.kdfAlgorithm || '');
    if (algo !== KDF_ALGORITHM_LABEL) throw new Error('Unsupported KDF algorithm: ' + algo);
    var iters = parseInt(envelope.kdf_iterations || envelope.kdfIterations || 0, 10);
    if (isNaN(iters) || iters < MIN_KDF_ITERATIONS) throw new Error('KDF iterations below minimum threshold');
    var salt = base64Decode(envelope.kdf_salt || envelope.kdfSalt || '');
    var iv = base64Decode(envelope.cipher_iv || envelope.cipherIv || '');
    var cipherWithTag = base64Decode(envelope.ciphertext || '');
    if (salt.length < 16 || iv.length !== 12 || cipherWithTag.length < 16) {
      throw new Error('Malformed vault envelope parameters');
    }
    var key = pbkdf2HmacSha256(pass, salt, iters, 32);
    var aad = utf8Bytes('soartv-vault-v1:' + algo + ':' + iters);
    var ptBytes = aes256GcmDecrypt(key, iv, cipherWithTag, aad);
    var parsed = JSON.parse(utf8Decode(ptBytes));
    var out = {}, k;
    if (parsed && typeof parsed === 'object') {
      for (k in parsed) {
        if (Object.prototype.hasOwnProperty.call(parsed, k) && parsed[k] !== null && parsed[k] !== undefined) {
          out[k] = String(parsed[k]);
        }
      }
    }
    return out;
  }

  /** Generates `len` cryptographically strong random bytes when available, with Math.random fallback. */
  function randomBytes(len) {
    var out = new Array(len), i;
    if (typeof window !== 'undefined' && window.crypto && window.crypto.getRandomValues && typeof Uint8Array !== 'undefined') {
      var buf = new Uint8Array(len);
      window.crypto.getRandomValues(buf);
      for (i = 0; i < len; i++) out[i] = buf[i];
      return out;
    }
    for (i = 0; i < len; i++) out[i] = Math.floor(Math.random() * 256) & 0xff;
    return out;
  }

  /** Generates an RFC 4122 Version 4 UUID string. */
  function uuidV4() {
    var b = randomBytes(16);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var h = bytesToHex(b, false);
    return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20, 32);
  }

  return {
    DEFAULT_VAULT_ID: DEFAULT_VAULT_ID,
    HOUSEHOLD_VAULT_ID: HOUSEHOLD_VAULT_ID,
    PROFILE_VAULT_PREFIX: PROFILE_VAULT_PREFIX,
    KDF_ALGORITHM_LABEL: KDF_ALGORITHM_LABEL,
    DEFAULT_KDF_ITERATIONS: DEFAULT_KDF_ITERATIONS,
    MIN_KDF_ITERATIONS: MIN_KDF_ITERATIONS,
    utf8Bytes: utf8Bytes,
    utf8Decode: utf8Decode,
    sha256Bytes: sha256Bytes,
    sha256Hex: sha256Hex,
    hmacSha256Bytes: hmacSha256Bytes,
    hmacSha256Hex: hmacSha256Hex,
    pbkdf2HmacSha256: pbkdf2HmacSha256,
    base64Url: base64Url,
    base64Encode: base64Encode,
    base64Decode: base64Decode,
    aes256GcmEncrypt: aes256GcmEncrypt,
    aes256GcmDecrypt: aes256GcmDecrypt,
    deriveHouseholdVaultPassphrase: deriveHouseholdVaultPassphrase,
    profileVaultId: profileVaultId,
    encryptVaultMap: encryptVaultMap,
    decryptVaultMap: decryptVaultMap,
    randomBytes: randomBytes,
    uuidV4: uuidV4
  };
})();
