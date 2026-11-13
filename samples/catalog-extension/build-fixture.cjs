// CC0-1.0. Build a ROM-only Game Boy homebrew cartridge and Retra API 1 repository.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const zlib = require('node:zlib');
function cartridge(){
  const rom = Buffer.alloc(32768);
  Buffer.from('00c35001','hex').copy(rom,0x100);
  // Hardware boot ROMs require these fixed cartridge header bytes.
  Buffer.from('ceed6666cc0d000b03730083000c000d0008111f8889000edccc6ee6dddd d999bbbb67636e0eecccdddc999fbbb9333e'.replaceAll(' ',''),'hex').copy(rom,0x104);
  rom.write('RETRA PAD',0x134);
  const code = [], labels = new Map(), jumps = [];
  const emit = bytes => code.push(...Buffer.from(bytes.replaceAll(' ',''),'hex'));
  const label = name => labels.set(name,code.length);
  const jump = (opcode,name) => { emit(opcode + '00'); jumps.push([code.length - 1,name]); };
  emit('f3 31 fe ff'); // DI; LD SP,$FFFE.
  label('wait_lcd'); emit('f0 44 fe 90'); jump('38','wait_lcd');
  emit('af e0 40 21 00 80 06 10'); // LCD off; clear background tile zero.
  label('blank'); emit('22 05'); jump('20','blank');
  emit('3e ff 06 10'); // Tile one is a solid square.
  label('square'); emit('22 05'); jump('20','square');
  emit('21 00 98 01 00 04');
  label('map'); emit('af 22 0b 78 b1'); jump('20','map');
  emit('21 00 fe 06 a0');
  label('oam'); emit('af 22 05'); jump('20','oam');
  emit('3e e4 e0 47 e0 48 16 50 1e 58 3e 93 e0 40'); // Palettes, sprite coordinates, LCD on.
  label('frame'); emit('f0 44 fe 90'); jump('20','frame');
  emit('3e 20 e0 00 f0 00 f0 00 47'); // Read directional buttons, active low.
  for (const [mask,operation,next] of [['cb40','1c','left'],['cb48','1d','up'],['cb50','15','down'],['cb58','14','paint']]) {
    emit(mask); jump('20',next); emit(operation); label(next);
  }
  emit('21 00 fe 7a 22 7b 22 3e 01 22 af 22'); // Update sprite in VBlank.
  label('end_vblank'); emit('f0 44 fe 90'); jump('30','end_vblank'); jump('18','frame');
  for (const [offset,target] of jumps) { const distance = labels.get(target) - offset - 1; assert(distance >= -128 && distance <= 127); code[offset] = distance & 255; }
  Buffer.from(code).copy(rom,0x150);
  let check = 0;
  for (const byte of rom.subarray(0x134,0x14d)) check = (check - byte - 1) & 255;
  rom[0x14d] = check;
  rom.writeUInt16BE(rom.reduce((sum,byte) => sum + byte,0) & 65535,0x14e);
  assert.equal(rom.length,32768); assert.equal(rom[0x150],0xf3);
  return rom;
}
// Minimal dependency-free PNG writer (8-bit RGB) for the repository artwork.
function png(width,height,pixel){
  const crcTable = Array.from({length:256},(_,n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = bytes => { let c = 0xffffffff; for (const byte of bytes) c = crcTable[(c ^ byte) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type,data) => {
    const body = Buffer.concat([Buffer.from(type),data]);
    const head = Buffer.alloc(4); head.writeUInt32BE(data.length);
    const tail = Buffer.alloc(4); tail.writeUInt32BE(crc(body));
    return Buffer.concat([head,body,tail]);
  };
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) raw.set(pixel(x,y),y * (width * 3 + 1) + 1 + x * 3);
  const header = Buffer.alloc(13); header.writeUInt32BE(width,0); header.writeUInt32BE(height,4); header[8] = 8; header[9] = 2;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(raw,{level:9})),chunk('IEND',Buffer.alloc(0))]);
}
// Classic four-shade handheld palette, lightest to darkest.
const shades = [[155,188,15],[139,172,15],[48,98,48],[15,56,15]];
function artwork(){
  // Exactly what the cartridge draws: a dark 8x8 sprite at (72,72) on a blank 160x144 screen, scaled 2x.
  const screenshot = png(320,288,(x,y) => (x >> 1) >= 72 && (x >> 1) < 80 && (y >> 1) >= 72 && (y >> 1) < 80 ? shades[3] : shades[0]);
  const cover = png(240,320,(x,y) => {
    const square = x >= 84 && x < 156 && y >= 92 && y < 164;
    const pad = (x >= 102 && x < 138 && y >= 220 && y < 292) || (x >= 84 && x < 156 && y >= 238 && y < 274);
    const grid = x % 24 === 0 || y % 24 === 0;
    return square ? shades[3] : pad ? shades[2] : grid ? shades[1] : shades[0];
  });
  const icon = png(96,96,(x,y) => x >= 32 && x < 64 && y >= 32 && y < 64 ? shades[0] : (x + y) % 12 < 2 ? shades[2] : shades[3]);
  return {screenshot,cover,icon};
}
function build(base,output){
  assert(new URL(base).protocol === 'https:' && base.endsWith('/'));
  fs.mkdirSync(output,{recursive:true});
  const digest = bytes => createHash('sha256').update(bytes).digest('hex');
  function write(name,value){ const data = Buffer.from(JSON.stringify(value,null,2) + '\n'); fs.writeFileSync(path.join(output,name),data); return digest(data); }
  const rom = cartridge(); fs.writeFileSync(path.join(output,'retra-pad.gb'),rom);
  const art = artwork();
  for (const [name,data] of Object.entries(art)) fs.writeFileSync(path.join(output,`${name}.png`),data);
  write('catalog.json',{apiVersion:1,games:[{
    id:'retra-pad',title:'Retra Pad',platform:'GB',developer:'Retra contributors',publisher:'Retra contributors',
    year:'2026',genre:'Demo',language:'en',region:'World',gameType:'homebrew',popularity:1,updatedAt:1794528000000,
    description:'A CC0 homebrew test cartridge. Move the square using the D-pad.',
    cover:base+'cover.png',screenshots:[base+'screenshot.png'],
    download:{url:base+'retra-pad.gb',sha256:digest(rom),size:rom.length,fileName:'retra-pad.gb',authorized:true,license:'CC0-1.0',rightsUrl:base+'LICENSE.txt'}
  }]});
  const sha256 = write('extension.json',{apiVersion:1,id:'org.retra.pad',versionCode:1,sources:[{
    id:'homebrew',name:'Retra Homebrew Sample',platforms:['GB'],latest:true,catalogUrl:base+'catalog.json',icon:base+'icon.png'
  }]});
  write('index.json',{schemaVersion:1,name:'Retra homebrew sample',extensions:[{
    id:'org.retra.pad',name:'Retra Homebrew Sample',publisher:'Retra contributors',versionCode:1,versionName:'1.0',format:'retra.catalog',apiMin:1,apiMax:1,
    platforms:['GB'],packageUrl:base+'extension.json',sha256,icon:base+'icon.png'
  }]});
  fs.writeFileSync(path.join(output,'LICENSE.txt'),'Retra Pad source, cartridge program, artwork, and sample catalog metadata are dedicated to the public domain under CC0 1.0.\nhttps://creativecommons.org/publicdomain/zero/1.0/\nThe fixed Game Boy header bytes are hardware compatibility data; no commercial game code, art, music, or ROM is included.\n');
}
// Usage: node build-fixture.cjs [https-base-url/] [output-dir]
// Defaults rebuild the Android test fixture; pass both to build a publishable repository.
if (require.main === module) build(process.argv[2] || 'https://example.org/retra/',path.resolve(process.argv[3] || path.join(__dirname,'../../app/src/androidTest/assets/browse')));
module.exports = {build,cartridge,png};
