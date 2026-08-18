const $ = (id) => document.getElementById(id);
let dashboard = null;
let toastTimer = null;

async function api(path, options = {}) {
  const response = await fetch(path, {headers:{"Content-Type":"application/json"}, ...options});
  const body = await response.json();
  if (!body.success) throw new Error(body.message || "请求失败");
  return body;
}

function toast(message, bad = false) {
  const el = $("toast");
  el.textContent = message;
  el.className = `toast show${bad ? " bad" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.className = "toast", 3500);
}

function esc(value) {
  return String(value ?? "").replace(/[&<>'"]/g, ch => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[ch]));
}

function statusLabel(status) {
  return ({PREPARING:"准备中",PENDING:"排队中",IN_PROGRESS:"分析中",PARTIAL_SUCCESS:"部分完成",COMPLETED:"已完成",FAILED:"失败",UNKNOWN:"未知"})[status] || status;
}

function selectedThreshold() {
  const input = $("bulkThreshold");
  const value = Math.max(0, Math.min(100, Math.round(Number(input.value) || 0)));
  input.value = value;
  return value;
}

function confidencePercent(suggestion) {
  if (Number.isFinite(suggestion.consensusPercent)) return suggestion.consensusPercent;
  return Math.round(Math.max(suggestion.introConsensus || 0, suggestion.endingConsensus || 0) * 100);
}

function isQualified(suggestion, threshold = selectedThreshold()) {
  const completed = ["COMPLETED","PARTIAL_SUCCESS"].includes(suggestion.analysisStatus);
  const hasSuggestion = (suggestion.skipOpening || 0) > 0 || (suggestion.skipEnding || 0) > 0;
  return completed && hasSuggestion && confidencePercent(suggestion) >= threshold;
}

function render(data) {
  dashboard = data;
  $("baseUrl").value = data.baseUrl || $("baseUrl").value;
  $("username").value = data.username || $("username").value;
  $("connectionPill").textContent = data.configured ? "已连接" : "尚未连接";
  $("connectionPill").className = `pill${data.configured ? "" : " muted"}`;
  $("capabilityText").textContent = data.chromaprintAvailable ? "Chromaprint 可用" : data.ffmpegAvailable ? "FFmpeg 可用 · 缺少 Chromaprint" : "未检测到 FFmpeg";

  ["autoApply","overwriteExisting","scheduledScan"].forEach(id => $(id).checked = !!data[id]);
  ["scanIntervalHours","minimumEpisodes","consensusThreshold","toleranceSeconds"].forEach(id => $(id).value = data[id]);
  $("scanStage").textContent = data.scan.stage || "等待扫描";
  $("discovered").textContent = data.scan.discovered || 0;
  $("queued").textContent = data.scan.queued || 0;
  $("scanButton").disabled = !!data.scan.running || !data.configured;
  $("forceScanButton").disabled = !!data.scan.running || !data.configured;
  $("scanError").textContent = data.scan.lastError || "";
  $("scanError").classList.toggle("hidden", !data.scan.lastError);

  const seasons = data.seasons || [];
  const threshold = selectedThreshold();
  const qualified = seasons.filter(s => isQualified(s, threshold));
  const visibleSeasons = $("onlyQualified").checked ? qualified : seasons;
  $("seasonCount").textContent = `达标 ${qualified.length} / 共 ${seasons.length} 季`;
  $("bulkApplyButton").disabled = !data.configured || qualified.length === 0;
  if (!seasons.length) {
    $("seasonRows").innerHTML = '<tr><td class="empty" colspan="7">连接后点击扫描，分析结果会显示在这里。</td></tr>';
    return;
  }
  if (!visibleSeasons.length) {
    $("seasonRows").innerHTML = `<tr><td class="empty" colspan="7">没有一致率达到 ${threshold}% 且可应用的剧季。</td></tr>`;
    return;
  }
  $("seasonRows").innerHTML = visibleSeasons.map(s => {
    const confidence = confidencePercent(s);
    const canApply = ["COMPLETED","PARTIAL_SUCCESS"].includes(s.analysisStatus);
    const active = ["PENDING","IN_PROGRESS"].includes(s.analysisStatus);
    const displayPercent = active ? (s.progressPercent || 0) : confidence;
    const progressCount = s.progressTotal ? ` · ${s.progressCompleted || 0}/${s.progressTotal}` : "";
    const progressText = active ? `${s.progressStage || (s.analysisStatus === "PENDING" ? "等待分析" : "分析中")}${progressCount}` : "";
    return `<tr>
      <td class="title-cell"><b>${esc(s.tvTitle || "未命名")}</b><small>第 ${s.seasonNumber ?? 0} 季 · ${s.episodeCount} 集</small></td>
      <td><span class="badge ${s.safe ? "good" : canApply ? "warn" : ""}">${esc(statusLabel(s.analysisStatus))}</span>${active ? `<small class="status-live">${esc(progressText)}</small>` : ""}</td>
      <td class="seconds">${s.skipOpening ? `${s.skipOpening} 秒` : "—"}<br><small>${s.introSamples} 个样本</small></td>
      <td class="seconds">${s.skipEnding ? `${s.skipEnding} 秒` : "—"}<br><small>${s.endingSamples} 个样本</small></td>
      <td><div class="confidence"><span>${displayPercent}%</span><span class="bar"><i style="width:${displayPercent}%"></i></span></div></td>
      <td><span title="${esc(s.applyStatus)}">${esc(active ? progressText : (s.applyStatus || s.reason))}</span></td>
      <td><button class="apply" data-guid="${esc(s.seasonGuid)}" data-safe="${s.safe}" ${canApply ? "" : "disabled"}>${s.safe ? "应用" : "复核应用"}</button></td>
    </tr>`;
  }).join("");
  document.querySelectorAll("button.apply").forEach(button => button.addEventListener("click", () => applySeason(button)));
}

async function refresh(silent = true) {
  try {
    const result = await api("/api/autoskip/dashboard");
    render(result.data);
  } catch (error) {
    if (!silent) toast(error.message, true);
  }
}

$("connectForm").addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.submitter;
  button.disabled = true;
  try {
    await api("/api/autoskip/connect", {method:"POST", body:JSON.stringify({baseUrl:$("baseUrl").value,username:$("username").value,password:$("password").value})});
    $("password").value = "";
    toast("连接成功，凭据已在本机加密保存");
    await refresh(false);
  } catch (error) { toast(error.message, true); }
  finally { button.disabled = false; }
});

$("settingsForm").addEventListener("submit", async event => {
  event.preventDefault();
  try {
    const payload = {autoApply:$("autoApply").checked,overwriteExisting:$("overwriteExisting").checked,scheduledScan:$("scheduledScan").checked,scanIntervalHours:+$("scanIntervalHours").value,minimumEpisodes:+$("minimumEpisodes").value,consensusThreshold:+$("consensusThreshold").value,toleranceSeconds:+$("toleranceSeconds").value};
    await api("/api/autoskip/settings", {method:"POST",body:JSON.stringify(payload)});
    toast("策略已保存");
    await refresh();
  } catch (error) { toast(error.message, true); }
});

async function scan(force) {
  if (force && !confirm("这会重新分析已经完成的全部剧季，耗时可能较长。继续吗？")) return;
  try {
    await api(`/api/autoskip/scan?force=${force}`, {method:"POST",body:"{}"});
    toast(force ? "已开始重新分析全部剧季" : "已开始扫描未处理剧季");
    await refresh(false);
  } catch (error) { toast(error.message, true); }
}
$("scanButton").addEventListener("click", () => scan(false));
$("forceScanButton").addEventListener("click", () => scan(true));

async function applySeason(button) {
  const force = button.dataset.safe !== "true";
  if (force && !confirm("该剧季的一致率未达到安全阈值。确认按当前建议值写入吗？")) return;
  button.disabled = true;
  try {
    const result = await api(`/api/autoskip/apply/${encodeURIComponent(button.dataset.guid)}?force=${force}`, {method:"POST",body:"{}"});
    toast(result.message || "已应用");
    await refresh();
  } catch (error) { toast(error.message, true); button.disabled = false; }
}

async function applyQualified() {
  const threshold = selectedThreshold();
  const eligible = (dashboard?.seasons || []).filter(s => isQualified(s, threshold));
  if (!eligible.length) {
    toast(`没有一致率达到 ${threshold}% 的可应用剧季`, true);
    return;
  }
  const existingPolicy = dashboard?.overwriteExisting ? "会覆盖已有手动设置" : "不会覆盖已有手动设置";
  if (!confirm(`将一键应用 ${eligible.length} 个剧季（一致率 ≥ ${threshold}%），${existingPolicy}。继续吗？`)) return;

  const button = $("bulkApplyButton");
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "应用中…";
  try {
    const result = await api("/api/autoskip/apply-bulk", {
      method:"POST",
      body:JSON.stringify({minimumPercent:threshold})
    });
    const failures = result.data?.failures || [];
    toast(result.message + (failures.length ? `；首个失败：${failures[0]}` : ""), failures.length > 0);
    await refresh();
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.textContent = originalText;
    if (dashboard) render(dashboard);
  }
}

$("bulkThreshold").addEventListener("change", () => dashboard && render(dashboard));
$("onlyQualified").addEventListener("change", () => dashboard && render(dashboard));
$("bulkApplyButton").addEventListener("click", applyQualified);

refresh(false);
setInterval(() => refresh(true), 5000);
