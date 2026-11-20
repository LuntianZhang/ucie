// Behavioral checks without a browser. Run: node test-runtime.cjs
const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'ucie-runtime.html'),'utf8');
const catalog=html.match(/<script id="source-catalog" type="application\/json">([\s\S]*?)<\/script>/)[1];
const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]).join('\n');
const nodes=new Map();function node(id){if(!nodes.has(id))nodes.set(id,{id,value:({group:'all',speed:'2200',catalogKind:'all',regKind:'all',traceRadix:'hex',regInput:'0x1'})[id]||'',textContent:id==='source-catalog'?catalog:'',innerHTML:'',checked:false,classList:{toggle(){}},setAttribute(){},addEventListener(){},querySelector(){return null},scrollIntoView(){},click(){}});return nodes.get(id)}
const ctx={assert,document:{getElementById:node,body:node('body'),addEventListener(){},createElement(){return node('anchor')}},structuredClone,clearInterval,setInterval,Blob,URL,setTimeout};vm.createContext(ctx);
vm.runInContext(scripts+`
assert.equal(META.FDI.length,36);assert.equal(META.RDI.length,31);
let count=0;
for(si=0;si<scenes.length;si++)for(step=0;step<scenes[si].steps.length;step++){
 for(bus of ['FDI','RDI']){for(const s of META[bus])assert.notEqual(scenes[si].steps[step].values[bus][s.name],undefined);render();}count++;
}
si=0;step=0;bus='FDI';render();assert.equal(filtered().length,36);
$('next').onclick();assert.equal(step,1);$('reset').onclick();assert.equal(step,0);
for(const k of ['all','内部寄存器','接口 / Bundle','connections','files']){$('catalogKind').value=k;drawCatalog();assert(catalogRows().length>0)}
$('catalogQuery').value='retryTrainingAmt';$('catalogKind').value='all';drawCatalog();assert(catalogRows().length>0);
for(let i=0;i<CATALOG.fields.length;i++)selectReg(i);
let find=(name)=>CATALOG.fields.findIndex(f=>f.name===JSON.stringify(name));
selectReg(find('uncorr_err_status'));assert.equal(regState.value,0n);$('regInput').value='0x3';regAction('hardware');assert.equal(regState.value,3n);regAction('link');assert.equal(regState.value,3n);$('regInput').value='0x1';regAction('write');assert.equal(regState.value,2n);regAction('reset');assert.equal(regState.value,0n);
selectReg(find('uncorr_err_mask'));assert.equal(regState.value,63n);$('regInput').value='0';regAction('write');regAction('link');assert.equal(regState.value,0n);regAction('reset');assert.equal(regState.value,63n);
selectReg(find('fatal_proto_report_en'));assert.equal(regState.value,0n);$('regInput').value='1';regAction('write');assert.equal(regState.value,1n);regAction('link');assert.equal(regState.value,0n);
selectReg(CATALOG.fields.findIndex(f=>f.kind==='RWautoClear'));$('regInput').value='1';regAction('write');assert.equal(regState.value,1n);regAction('done');assert.equal(regState.value,0n);
loadTrace(teachingVCD,'test');assert.equal(trace.signals.length,9);assert.equal(trace.times.length,10);ti=3;renderTrace();assert.equal(atTime(trace.histories.get('!'),30n),'1');assert.equal(atTime(trace.histories.get('"'),50n),'000001');
$('traceQuery').value='regs';assert.equal(traceRows().length,4);$('traceQuery').value='';$('traceChanged').checked=true;assert.equal(traceRows().length,3);$('traceChanged').checked=false;
$('traceNext').onclick();assert.equal(ti,4);$('tracePrev').onclick();assert.equal(ti,3);
const custom='$timescale 10 ps $end\\n$scope module top $end\\n$var wire 128 ! wide $end\\n$var wire 128 ! alias $end\\n$var reg 1 xx flag $end\\n$upscope $end\\n$enddefinitions $end\\n$dumpvars\\nb1 !\\nx xx\\n$end\\n#9007199254740993\\nb'+ '1'.repeat(128)+' !\\n1xx\\n#9007199254740994\\n$dumpoff\\n$end\\n#9007199254740995\\n$dumpon\\nbz !\\n0xx\\n$end';
const v=parseVCD(custom);assert.equal(v.signals.length,3);assert.equal(v.times[1],9007199254740993n);assert.equal(formatVCD(atTime(v.histories.get('!'),v.times[1]),v.signals[0],'hex'),'0x'+'f'.repeat(32));assert.equal(atTime(v.histories.get('!'),v.times[2]),'x');assert.equal(atTime(v.histories.get('!'),v.times[3]),'z');
assert.throws(()=>parseVCD('bad'));assert.throws(()=>parseVCD('$var wire 1 ! a $end $enddefinitions $end #2 1! #1 0!'));
this.report={steps:count,sourceFiles:CATALOG.files.length,registerDefinitions:CATALOG.fields.length,declarations:CATALOG.declarations.length};
`,ctx);console.log('PASS',ctx.report,'; register reset/write semantics, source filters, VCD alias/X/Z/128-bit/big-time checks');
