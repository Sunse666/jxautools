# 从 CourseQuery.py 原样复制 _encrypt_cas_password 的算法，生成用于跨实现比对的基准值
MODULUS_HEX = "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eaeb670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b6431604bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"

def _digits_16(value):
    if value == 0:
        return [0]
    digits = []
    while value > 0:
        digits.append(value & 0xFFFF)
        value >>= 16
    return digits

def encrypt(password_text, modulus_hex=MODULUS_HEX):
    public_exponent = int("010001", 16)
    modulus = int(modulus_hex, 16)
    chunk_size = 2 * (len(_digits_16(modulus)) - 1)
    char_codes = [ord(ch) for ch in str(password_text or "")]
    while len(char_codes) % chunk_size != 0:
        char_codes.append(0)
    encrypted_blocks = []
    for offset in range(0, len(char_codes), chunk_size):
        block_value = 0
        digit_index = 0
        cursor = offset
        while cursor < offset + chunk_size:
            digit_value = char_codes[cursor]; cursor += 1
            digit_value += char_codes[cursor] << 8; cursor += 1
            block_value += digit_value << (16 * digit_index)
            digit_index += 1
        cipher_value = pow(block_value, public_exponent, modulus)
        cipher_digits = _digits_16(cipher_value)
        encrypted_blocks.append("".join(f"{digit:04x}" for digit in reversed(cipher_digits)))
    return " ".join(encrypted_blocks)

if __name__ == "__main__":
    m = int(MODULUS_HEX, 16)
    print(f"modulus_hex_len={len(MODULUS_HEX)} bitLength={m.bit_length()} digits16={len(_digits_16(m))} chunk_size={2*(len(_digits_16(m))-1)}")
    for pw in ["123456", "a", "test1234", "密码测试123", "a"*126, "b"*127]:
        out = encrypt(pw)
        print(f"\npw={pw!r} len={len(pw)} blocks={len(out.split(' '))}")
        print(out)
