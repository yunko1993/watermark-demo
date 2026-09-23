const $ = id => document.getElementById(id);
let previewUrl;
function message(text, error = false) {
  $('message').textContent = text;
  $('message').classList.toggle('error', error);
}
async function checked(response) {
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `请求失败（${response.status}）`);
  }
  return response;
}
async function refresh() {
  try {
    const status = await (await checked(await fetch('/api/status'))).json();
    $('watermark').textContent = status.watermark;
    $('connection').textContent = status.minioConnected ? '● MinIO 已连接' : '○ MinIO 未连接';
    $('connection').title = status.message;
  } catch (error) {
    $('connection').textContent = '应用连接失败';
    message(error.message, true);
  }
}
async function busy(action) {
  document.querySelectorAll('button').forEach(button => button.disabled = true);
  try { await action(); } catch (error) { message(error.message, true); }
  finally { document.querySelectorAll('button').forEach(button => button.disabled = false); }
}
$('refresh').addEventListener('click', () => busy(refresh));
$('uploadForm').addEventListener('submit', event => {
  event.preventDefault();
  busy(async () => {
    const file = $('file').files[0];
    if (!file || !/\.(pdf|docx|doc|wps)$/i.test(file.name) || !file.size || file.size > 20 * 1024 * 1024) {
      throw new Error('请选择非空且不超过 20 MB 的 PDF、DOCX、DOC 或 WPS 文件');
    }
    const form = new FormData(); form.append('file', file);
    message('正在校验并上传文件原件…');
    const result = await (await checked(await fetch('/api/files', {method: 'POST', body: form}))).json();
    $('objectName').value = result.objectName;
    $('watermark').textContent = result.watermark;
    $('viewer').hidden = true; $('viewer').removeAttribute('src'); $('empty').hidden = false;
    $('previewLabel').textContent = '等待预览新文件';
    if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
    message(/\.(doc|wps)$/i.test(result.objectName) ? 'DOC/WPS 上传成功！可分别点击 Aspose 下载和 Spire 下载进行对比。' : /\.docx$/i.test(result.objectName) ? 'DOCX 上传成功！请下载后用 Word / WPS 查看。' : '上传成功！点击预览，对比原件和水印效果。');
    await refresh();
  });
});
document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => busy(async () => {
  const objectName = $('objectName').value.trim();
  if (!objectName) throw new Error('请先上传文件或填写 MinIO 对象名');
  const action = button.dataset.action;
  const engine = action === 'download-aspose' ? 'aspose' : action === 'download-spire' ? 'spire' : null;
  const isWord = /\.(docx?|wps)$/i.test(objectName);
  const isLegacyWord = /\.(doc|wps)$/i.test(objectName);
  if (isWord && action === "watermark") throw new Error("Word 文件暂不支持在线预览，请点击下载水印版，用 Word / WPS 查看");
  if (engine && !isLegacyWord) throw new Error('Aspose / Spire 对比下载仅用于 DOC 和 WPS 文件');
  const engineLabel = engine === 'aspose' ? 'Aspose' : engine === 'spire' ? 'Spire' : '';
  message(action === 'original' ? '正在读取原件…' : `正在使用${engineLabel ? ` ${engineLabel} ` : ''}生成当天水印…`);
  const params = new URLSearchParams({objectName, original: action === 'original', preview: action === 'watermark'});
  const endpoint = engine ? `/api/files/download/${engine}` : '/api/files/download';
  const response = await checked(await fetch(`${endpoint}?${params}`));
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  if (action === 'download' || engine || isWord) {
    const link = document.createElement('a');
    const prefix = action === 'original' ? '原件' : `${engineLabel || ''}水印`;
    link.href = url; link.download = `${prefix}-${objectName.split('/').pop()}`;
    document.body.appendChild(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    message(isWord ? `${engineLabel || 'Word'} 文件已下载，请用 Word / WPS 打开查看（打印布局）。` : '水印 PDF 已生成，已发起浏览器下载。');
  } else {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = url;
    $('viewer').src = url; $('viewer').hidden = false; $('empty').hidden = true;
    $('previewLabel').textContent = action === 'original' ? '原件 · 无水印' : '水印版 · 当天日期';
    message('PDF 已生成并交给浏览器预览；若预览空白，可下载查看。');
  }
  await refresh();
})));
refresh();
