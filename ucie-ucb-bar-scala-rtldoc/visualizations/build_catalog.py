"""Embed a conservative source index; not a Scala elaborator or netlist parser."""
import json,re,hashlib
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]/'ucie-ucb-bar/scala/src/uciedigital'

def mask(s):
    # Keep all offsets/line numbers intact, excluding comments and string contents.
    pat=r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"'
    return re.sub(pat,lambda m:re.sub(r'[^\n]',' ',m[0]),s)

def endexpr(s,start):
    m=mask(s);stack=[]
    for i in range(start,len(s)):
        c=m[i]
        if c in '([{':stack.append(c)
        elif c in ')]}':
            if not stack:return i
            stack.pop()
        if c in '\n;' and not stack and s[start:i].strip():return i
    return len(s)

def args(s):
    m=mask(s);depth=0;start=0;out=[]
    for i,c in enumerate(m):
        if c in '([{':depth+=1
        if c in ')]}':depth-=1
        if c==',' and depth==0:out.append(s[start:i].strip());start=i+1
    out.append(s[start:].strip());return out

def close(s,pos):
    m=mask(s);depth=1
    for i in range(pos,len(s)):
        if m[i]=='(':depth+=1
        elif m[i]==')':
            depth-=1
            if depth==0:return i
    raise ValueError('Unbalanced call')

files=[];decls=[];fields=[];connections=[];offsets=[]
for p in sorted(ROOT.rglob('*.scala')):
    s=p.read_text();m=mask(s);rel=str(p.relative_to(ROOT));lines=s.splitlines()
    files.append(dict(path=rel,content=s,sha256=hashlib.sha256(s.encode()).hexdigest()))
    def loc(at):return dict(file=rel,line=s.count('\n',0,at)+1)
    classes=list(re.finditer(r'\b(class|object|trait)\s+(\w+)',m))
    def owner(at):return next((x[2] for x in reversed(classes) if x.start()<at),p.stem)
    for x in re.finditer(r'\bval\s+(\w+|\([^\n]+?\))\s*(?::[^=\n]+)?=(?!=|>)',m):
        expr=s[x.end():endexpr(s,x.end())].strip();name=x[1];kind='声明 / 组合表达式'
        if re.search(r'\bReg(?:Init|Next|Enable)?\s*\(',mask(expr)):kind='内部寄存器'
        elif re.search(r'\b(?:Input|Output|Flipped|IO|Decoupled|Valid|Vec)\s*\(',mask(expr)):kind='接口 / Bundle'
        elif re.search(r'\b(?:Module|LazyModule)\s*\(',mask(expr)):kind='模块实例'
        elif owner(x.start()).endswith('Offsets') or owner(x.start())=='PhyOffsets':kind='地址偏移'
        d=dict(name=name,owner=owner(x.start()),kind=kind,expression=expr,**loc(x.start()));decls.append(d)
        if kind=='地址偏移':offsets.append(d)
    for x in re.finditer(r'\bf\.(RO|HWInit|RsvdP|RsvdZ|RW|RWS|ROS|RW1C|RW1CS|RWautoClear|RWL|RWO)\s*\(',m):
        e=close(s,x.end());a=args(s[x.end():e]);named={};pos=[]
        for v in a:
            q=re.match(r'^(\w+)\s*=\s*(.*)$',v,re.S)
            if q:named[q[1]]=q[2]
            else:pos.append(v)
        typ=x[1];keys=['width','value','name','description'] if typ in ['RO','HWInit'] else ['width','hwSet','name','description'] if typ in ['RW1C','RW1CS'] else ['hwDone','ignoreWhen','name','description'] if typ=='RWautoClear' else ['width','name'] if typ.startswith('Rsvd') else ['width','init','lock','name','description'] if typ=='RWL' else ['width','init','name','description']
        v=dict(zip(keys,pos));v.update(named)
        width='1' if typ=='RWautoClear' else v.get('width','未知')
        reset='0' if typ in ['RW1C','RW1CS','RWautoClear','RsvdP','RsvdZ'] else v.get('init',v.get('value','动态输入；无存储复位值')) if typ!='RO' else '动态输入；无存储复位值'
        fields.append(dict(name=v.get('name','保留字段'),owner=owner(x.start()),kind=typ,width=width,reset=reset,description=v.get('description','保留位'),expression=s[x.start():e+1],**loc(x.start())))
    for i,line in enumerate(lines):
        if ':=' in mask(line) or '<>' in mask(line):connections.append(dict(file=rel,line=i+1,expression=line.strip()))
data=dict(files=files,declarations=decls,fields=fields,connections=connections,offsets=offsets)
html=HERE/'ucie-runtime.html';s=html.read_text();block='<script id="source-catalog" type="application/json">'+json.dumps(data,ensure_ascii=False).replace('<','\\u003c')+'</script>'
s=re.sub(r'<script id="source-catalog" type="application/json">[\s\S]*?</script>','',s)
s=s.replace('</head>','</head>') # Existing page intentionally uses implicit head/body.
s=s.replace('<main>','<main>\n'+block,1);html.write_text(s)
print({k:len(v) for k,v in data.items()})
