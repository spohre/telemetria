"""Gera rsrc_windows_amd64.syso (ícone do .exe) a partir de telemetria.ico, sem ferramentas extras.

O Go embute automaticamente no executável de Windows qualquer arquivo .syso da pasta do pacote.
Este script monta um objeto COFF com uma seção .rsrc contendo RT_ICON + RT_GROUP_ICON.

Uso:  python gerar_syso.py telemetria.ico rsrc_windows_amd64.syso
"""
import struct
import sys

RT_ICON, RT_GROUP_ICON = 3, 14
IDIOMA = 0x0416            # pt-BR
IMAGE_REL_AMD64_ADDR32NB = 3


def ler_ico(caminho):
    dados = open(caminho, "rb").read()
    _, tipo, n = struct.unpack_from("<HHH", dados, 0)
    assert tipo == 1, "não é um arquivo .ico"
    imagens = []
    for i in range(n):
        w, h, cores, res, planos, bits, tam, off = struct.unpack_from("<BBBBHHII", dados, 6 + 16 * i)
        imagens.append(((w, h, cores, res, planos, bits), dados[off:off + tam]))
    return imagens


def alinhar(buf, n):
    while len(buf) % n:
        buf.append(0)


def montar_rsrc(imagens):
    """Devolve (bytes da seção, offsets dos campos que precisam de relocação)."""
    grupo = bytearray(struct.pack("<HHH", 0, 1, len(imagens)))
    for i, ((w, h, cores, res, planos, bits), blob) in enumerate(imagens, start=1):
        grupo += struct.pack("<BBBBHHIH", w, h, cores, res, planos, bits, len(blob), i)

    # (tipo, id, dados) ordenados por tipo e id, como o formato exige
    folhas = [(RT_ICON, i, blob) for i, (_, blob) in enumerate(imagens, start=1)]
    folhas.append((RT_GROUP_ICON, 1, bytes(grupo)))
    tipos = sorted({t for t, _, _ in folhas})

    # layout: raiz | dirs de tipo | dirs de idioma | entradas de dados | dados
    def tam_dir(n):
        return 16 + 8 * n

    off = tam_dir(len(tipos))
    off_tipo = {}
    for t in tipos:
        off_tipo[t] = off
        off += tam_dir(sum(1 for x in folhas if x[0] == t))
    off_idioma = {}
    for t, i, _ in folhas:
        off_idioma[(t, i)] = off
        off += tam_dir(1)
    off_entrada = {}
    for t, i, _ in folhas:
        off_entrada[(t, i)] = off
        off += 16
    off_dados = {}
    for t, i, blob in folhas:
        off = (off + 7) & ~7
        off_dados[(t, i)] = off
        off += len(blob)

    sec = bytearray()
    relocs = []

    def diretorio(entradas):
        sec.extend(struct.pack("<IIHHHH", 0, 0, 0, 0, 0, len(entradas)))
        for ident, alvo, subdir in entradas:
            sec.extend(struct.pack("<II", ident, alvo | (0x80000000 if subdir else 0)))

    diretorio([(t, off_tipo[t], True) for t in tipos])
    for t in tipos:
        diretorio([(i, off_idioma[(t, i)], True) for tt, i, _ in folhas if tt == t])
    for t, i, _ in folhas:
        diretorio([(IDIOMA, off_entrada[(t, i)], False)])
    for t, i, blob in folhas:
        relocs.append(len(sec))                       # OffsetToData é RVA → relocação
        sec.extend(struct.pack("<IIII", off_dados[(t, i)], len(blob), 0, 0))
    for t, i, blob in folhas:
        alinhar(sec, 8)
        assert len(sec) == off_dados[(t, i)]
        sec.extend(blob)
    alinhar(sec, 8)
    return bytes(sec), relocs


def montar_coff(secao, relocs):
    n_sec, cab, cab_sec = 1, 20, 40
    p_dados = cab + cab_sec
    p_relocs = p_dados + len(secao)
    p_simbolos = p_relocs + 10 * len(relocs)
    out = bytearray()
    out += struct.pack("<HHIIIHH", 0x8664, n_sec, 0, p_simbolos, 1, 0, 0)
    out += struct.pack("<8sIIIIIIHHI", b".rsrc", 0, 0, len(secao), p_dados, p_relocs, 0,
                       len(relocs), 0, 0x40000040)     # INITIALIZED_DATA | MEM_READ
    out += secao
    for r in relocs:
        out += struct.pack("<IIH", r, 0, IMAGE_REL_AMD64_ADDR32NB)   # símbolo 0 = .rsrc
    out += struct.pack("<8sIhHBB", b".rsrc", 0, 1, 0, 3, 0)            # símbolo da seção (STATIC)
    out += struct.pack("<I", 4)                                       # tabela de strings vazia
    return bytes(out)


if __name__ == "__main__":
    origem, destino = sys.argv[1], sys.argv[2]
    secao, relocs = montar_rsrc(ler_ico(origem))
    open(destino, "wb").write(montar_coff(secao, relocs))
    print(f"{destino}: {len(secao)} bytes de recursos, {len(relocs)} relocações")
