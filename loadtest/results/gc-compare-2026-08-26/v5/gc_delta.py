import sys, re

def parse(path):
    vals = {"pause_sum": 0.0, "pause_count": 0.0, "alloc": 0.0, "heap_used": 0.0}
    with open(path) as f:
        for line in f:
            if line.startswith('#'):
                continue
            m = re.match(r'^(\w+)\{([^}]*)\}\s+([\-0-9.eE]+)', line)
            if not m:
                m2 = re.match(r'^(\w+)\s+([\-0-9.eE]+)', line)
                if not m2:
                    continue
                name, val = m2.group(1), float(m2.group(2))
                labels = ""
            else:
                name, labels, val = m.group(1), m.group(2), float(m.group(3))
            if name == "jvm_gc_pause_seconds_sum":
                vals["pause_sum"] += val
            elif name == "jvm_gc_pause_seconds_count":
                vals["pause_count"] += val
            elif name == "jvm_gc_memory_allocated_bytes_total":
                vals["alloc"] += val
            elif name == "jvm_memory_used_bytes" and 'area="heap"' in labels:
                vals["heap_used"] += val
    return vals

before = parse(sys.argv[1])
after = parse(sys.argv[2])
cpu_log = sys.argv[3]
ksum_path = sys.argv[4]
block = sys.argv[5]
arm = sys.argv[6]

cpu_vals = []
try:
    with open(cpu_log) as f:
        for line in f:
            line = line.strip().rstrip('%')
            try:
                cpu_vals.append(float(line))
            except ValueError:
                pass
except FileNotFoundError:
    pass
avg_cpu = sum(cpu_vals) / len(cpu_vals) if cpu_vals else float('nan')

k6_cols = ["NA"] * 15
try:
    with open(ksum_path) as f:
        line = f.read().strip()
        if line:
            k6_cols = line.split()
except FileNotFoundError:
    pass

def col(i):
    try:
        return k6_cols[i]
    except IndexError:
        return "NA"

gc_pause_delta = after["pause_sum"] - before["pause_sum"]
gc_count_delta = after["pause_count"] - before["pause_count"]
alloc_delta_mb = (after["alloc"] - before["alloc"]) / (1024 * 1024)
heap_used_mb_end = after["heap_used"] / (1024 * 1024)

# 열: block arm gc_pause_sum_delta gc_pause_count_delta alloc_delta_mb heap_used_mb_end avg_cpu
#     start_p50 start_p99 end_p50 end_p99 iters failed dropped conflict
print(f"{block}\t{arm}\t{gc_pause_delta:.4f}\t{gc_count_delta:.0f}\t{alloc_delta_mb:.1f}\t{heap_used_mb_end:.1f}\t{avg_cpu:.1f}\t{col(3)}\t{col(5)}\t{col(7)}\t{col(9)}\t{col(11)}\t{col(12)}\t{col(13)}\t{col(14)}")
