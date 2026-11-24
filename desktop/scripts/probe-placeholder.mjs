// 直接问 pptxgenjs：不填占位符时，它会不会把自己的默认文案写进 slide xml？
// 我之前在代码注释里写了"实测过"，其实那次观测到的默认文案来自**模型输入**，
// 不是 pptxgenjs。这个脚本才是真正的验证。
import PptxGenJS from 'pptxgenjs'
import JSZip from 'jszip'

async function probe(fill) {
  const pptx = new PptxGenJS()
  pptx.defineLayout({ name: 'W', width: 13.333, height: 7.5 })
  pptx.layout = 'W'
  pptx.defineSlideMaster({
    title: 'M',
    background: { color: 'FFFFFF' },
    objects: [
      { placeholder: { text: '标题', options: { x: 0.75, y: 0.5, w: 11.83, h: 1.2, fontSize: 40 } } },
    ],
  })
  const s = pptx.addSlide({ masterName: 'M' })
  if (fill) s.addText('真标题', { placeholder: { text: '标题' } })
  const buf = await pptx.write({ outputType: 'nodebuffer' })
  const zip = await JSZip.loadAsync(buf)
  const xml = await zip.file('ppt/slides/slide1.xml').async('string')
  const texts = [...xml.matchAll(/<a:t>(.*?)<\/a:t>/gs)].map((m) => m[1])
  console.log(fill ? '填了空字符串/文字:' : '完全不填占位符:', JSON.stringify(texts))
}

await probe(false)
await probe(true)
