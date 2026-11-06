"""
Converts the DocAligner page-corner model (ONNX, Apache-2.0, github.com/DocsaidLab/DocAligner)
into the small binary format read by PageNet.java, so the app needs no ML runtime library.

    pip install onnx onnxsim numpy
    python tools/pagenet_export.py lcnet100_h_e_bifpn_256_fp32.onnx app/src/main/assets/pagenet.bin

Format (little-endian): "PGNT", version, op count, then ops. Weights are stored as float16.
"""
import struct
import sys

import numpy as np
import onnx
import onnxsim
from onnx import numpy_helper

CONV, RELU, SIGMOID, MAXPOOL, RESIZE, MEAN, ADDC, CLIP, MULC, MUL, WSUM = range(1, 12)


def main(src, dst):
    model, ok = onnxsim.simplify(onnx.load(src))
    assert ok
    g = model.graph
    const = {t.name: numpy_helper.to_array(t) for t in g.initializer}
    for n in g.node:
        if n.op_type == 'Constant':
            const[n.output[0]] = numpy_helper.to_array(n.attribute[0].t)
    consumers = {}
    for n in g.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    producer = {o: n for n in g.node for o in n.output}

    ids = {g.input[0].name: 0}

    def tid(name):
        if name not in ids:
            ids[name] = len(ids)
        return ids[name]

    out = bytearray()
    ops = 0
    skip = set()

    def i32(*v):
        out.extend(struct.pack('<%di' % len(v), *v))

    def f32(*v):
        out.extend(struct.pack('<%df' % len(v), *v))

    def f16(a):
        a = np.asarray(a, dtype=np.float32).ravel()
        i32(a.size)
        out.extend(a.astype('<f2').tobytes())

    for n in g.node:
        if n.op_type in ('Constant', 'Unsqueeze', 'Concat') or id(n) in skip:
            continue
        a = {x.name: onnx.helper.get_attribute_value(x) for x in n.attribute}
        t = n.op_type
        if t == 'Conv':
            w = const[n.input[1]]
            b = const[n.input[2]] if len(n.input) > 2 else np.zeros(w.shape[0], np.float32)
            outname = n.output[0]
            relu = 0
            c = consumers.get(outname, [])
            if len(c) == 1 and c[0].op_type == 'Relu':
                relu = 1
                skip.add(id(c[0]))
                outname = c[0].output[0]
            pads = a.get('pads', [0, 0, 0, 0])
            st = a.get('strides', [1, 1])
            dl = a.get('dilations', [1, 1])
            i32(CONV, tid(n.input[0]), tid(outname), w.shape[1] * a.get('group', 1), w.shape[0],
                w.shape[2], w.shape[3], st[0], st[1], pads[0], pads[1], pads[2], pads[3],
                a.get('group', 1), dl[0], dl[1], relu)
            f16(w)
            f16(b)
        elif t == 'Relu':
            i32(RELU, tid(n.input[0]), tid(n.output[0]))
        elif t == 'Sigmoid':
            i32(SIGMOID, tid(n.input[0]), tid(n.output[0]))
        elif t == 'MaxPool':
            assert a['kernel_shape'][0] == a['kernel_shape'][1] and a.get('ceil_mode', 0) == 0
            i32(MAXPOOL, tid(n.input[0]), tid(n.output[0]), a['kernel_shape'][0], a['strides'][0], a['pads'][0])
        elif t == 'Resize':
            assert a['mode'] == b'linear' and a['coordinate_transformation_mode'] == b'half_pixel'
            s = const[n.input[2]]
            i32(RESIZE, tid(n.input[0]), tid(n.output[0]))
            f32(float(s[2]), float(s[3]))
        elif t == 'ReduceMean':
            assert list(a['axes']) == [2, 3]
            i32(MEAN, tid(n.input[0]), tid(n.output[0]))
        elif t in ('Add', 'Mul') and (n.input[1] in const or n.input[0] in const):
            x, k = (n.input[0], n.input[1]) if n.input[1] in const else (n.input[1], n.input[0])
            assert const[k].size == 1
            i32(ADDC if t == 'Add' else MULC, tid(x), tid(n.output[0]))
            f32(float(const[k].ravel()[0]))
        elif t == 'Mul':
            i32(MUL, tid(n.input[0]), tid(n.input[1]), tid(n.output[0]))
        elif t == 'Clip':
            i32(CLIP, tid(n.input[0]), tid(n.output[0]))
            f32(float(const[n.input[1]]), float(const[n.input[2]]))
        elif t == 'Einsum':
            assert a['equation'] == b'i,i...->...'
            wts = const[n.input[0]]
            cat = producer[n.input[1]]
            assert cat.op_type == 'Concat'
            srcs = []
            for u in cat.input:
                un = producer[u]
                assert un.op_type == 'Unsqueeze'
                srcs.append(tid(un.input[0]))
            i32(WSUM, tid(n.output[0]), len(srcs), *srcs)
            f32(*[float(v) for v in wts])
        else:
            raise SystemExit('unsupported op ' + t)
        ops += 1
    head = b'PGNT' + struct.pack('<iii', 1, ops, len(ids))
    out_id = ids[g.output[0].name]
    with open(dst, 'wb') as f:
        f.write(head + struct.pack('<i', out_id) + bytes(out))
    print(ops, 'ops,', len(ids), 'tensors,', len(out) // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
