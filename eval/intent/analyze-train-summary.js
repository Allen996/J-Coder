const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, 'results', 'intent-per-row-train-20260826-192824.jsonl');
const lines = fs.readFileSync(file, 'utf8').split('\n').filter(Boolean);
const rows = lines.map(l => JSON.parse(l));

console.log('Total rows:', rows.length);

// READ_CODE mis-classifications
const readCode = rows.filter(r => r.gold_label === 'READ_CODE');
console.log('\nREAD_CODE total:', readCode.length);
console.log('READ_CODE correct:', readCode.filter(r => r.pred_label === 'READ_CODE').length);

// Detail by predicted label
const byPred = {};
readCode.forEach(r => {
  byPred[r.pred_label] = (byPred[r.pred_label] || 0) + 1;
});
console.log('READ_CODE → pred:', byPred);

// All READ_CODE mis-classified → CHAT_QA
const toChatQA = readCode.filter(r => r.pred_label === 'CHAT_QA');
console.log('\n=== READ_CODE → CHAT_QA (' + toChatQA.length + ') ===');
toChatQA.forEach(r => {
  console.log(`\n[${r.id}] ${r.input}`);
  console.log(`  pred=${r.pred_label} conf=${r.pred_conf.toFixed(3)} tier=${r.tier} fallback=${r.fallback}`);
  console.log(`  reason=${r.fallback_reason || '-'}`);
  console.log(`  kw_sug=${r.keyword_suggested} kw_score=${r.keyword_score}`);
  console.log(`  kw_hits=${JSON.stringify(r.keyword_hits)}`);
  console.log(`  neg=${JSON.stringify(r.negative_signals)}`);
  console.log(`  slot=${r.slot_score} slotVR=${r.slot_validation_outcome}`);
  console.log(`  raw=${r.raw_llm_conf} rules=${JSON.stringify(r.calibration_rules)}`);
});

console.log('\n=== READ_CODE → RUN_COMMAND (' + readCode.filter(r => r.pred_label === 'RUN_COMMAND').length + ') ===');
readCode.filter(r => r.pred_label === 'RUN_COMMAND').forEach(r => {
  console.log(`\n[${r.id}] ${r.input}`);
  console.log(`  pred=${r.pred_label} conf=${r.pred_conf.toFixed(3)} tier=${r.tier} fallback=${r.fallback}`);
  console.log(`  reason=${r.fallback_reason || '-'}`);
  console.log(`  kw_sug=${r.keyword_suggested} kw_score=${r.keyword_score}`);
  console.log(`  kw_hits=${JSON.stringify(r.keyword_hits)}`);
  console.log(`  neg=${JSON.stringify(r.negative_signals)}`);
  console.log(`  raw=${r.raw_llm_conf} rules=${JSON.stringify(r.calibration_rules)}`);
});

console.log('\n=== READ_CODE correctly classified (sample) ===');
readCode.filter(r => r.pred_label === 'READ_CODE').slice(0, 10).forEach(r => {
  console.log(`[${r.id}] ${r.input} | hits=${JSON.stringify(r.keyword_hits)} rules=${JSON.stringify(r.calibration_rules)}`);
});

// Patterns in misclassified (CHAT_QA direction)
console.log('\n=== Patterns for READ_CODE → CHAT_QA ===');
const keywordPattern = {};
toChatQA.forEach(r => {
  const k = (r.keyword_hits || []).sort().join(',');
  keywordPattern[k || '(none)'] = (keywordPattern[k || '(none)'] || 0) + 1;
});
Object.entries(keywordPattern)
  .sort((a,b) => b[1] - a[1])
  .forEach(([k, v]) => console.log(`  ${v}x  ${k}`));

// Calibration rules distribution
console.log('\n=== Calibration rules for READ_CODE misclassified ===');
const rulesPattern = {};
readCode.filter(r => r.pred_label !== 'READ_CODE').forEach(r => {
  const k = (r.calibration_rules || []).sort().join(',') || '(none)';
  rulesPattern[k] = (rulesPattern[k] || 0) + 1;
});
Object.entries(rulesPattern)
  .sort((a,b) => b[1] - a[1])
  .forEach(([k, v]) => console.log(`  ${v}x  ${k}`));

// Look at LLM raw conf distribution for misclassified
console.log('\n=== LLM raw conf distribution for READ_CODE → CHAT_QA ===');
const confBuckets = { '<0.5':0, '0.5-0.7':0, '0.7-0.85':0, '>0.85':0 };
toChatQA.forEach(r => {
  const c = r.raw_llm_conf;
  if (c < 0.5) confBuckets['<0.5']++;
  else if (c < 0.7) confBuckets['0.5-0.7']++;
  else if (c < 0.85) confBuckets['0.7-0.85']++;
  else confBuckets['>0.85']++;
});
console.log(confBuckets);

// Look at keyword suggested for misclassified
console.log('\n=== Keyword suggested vs LLM primary for READ_CODE → CHAT_QA ===');
toChatQA.forEach(r => {
  const llmPrimary = (r.llmConf !== undefined) ? '(see raw)' : '?';
  console.log(`  [${r.id}] llm_raw=${r.raw_llm_conf} kw_sug=${r.keyword_suggested} (LLM chose CHAT_QA)`);
});

// Check how often conflict triggered
console.log('\n=== CONFLICT_PENALTY triggered? ===');
const conflictYes = toChatQA.filter(r => (r.calibration_rules || []).includes('CONFLICT_PENALTY')).length;
console.log(`CONFLICT_PENALTY hit: ${conflictYes} / ${toChatQA.length}`);
console.log(`HIGH_CLIP hit: ${toChatQA.filter(r => (r.calibration_rules || []).includes('HIGH_CLIP')).length} / ${toChatQA.length}`);
console.log(`NO_EVIDENCE_CAP hit: ${toChatQA.filter(r => (r.calibration_rules || []).includes('NO_EVIDENCE_CAP')).length} / ${toChatQA.length}`);

// LLM primary for these (we can infer from raw_llm_conf and pred_label)
console.log('\n=== Did LLM also pick CHAT_QA? ===');
console.log(`LLM same as pred (CHAT_QA): ${toChatQA.length} (since pred is CHAT_QA and likely from LLM)`);
const llmProbablyChat = toChatQA.filter(r => r.raw_llm_conf >= 0.5).length;
console.log(`raw_llm_conf >= 0.5 (LLM did respond): ${llmProbablyChat}`);

// Look at WRITE_PROJECT → READ_CODE (false positives)
console.log('\n=== WRITE_PROJECT → READ_CODE (false positives) ===');
const writeToRead = rows.filter(r => r.gold_label === 'WRITE_PROJECT' && r.pred_label === 'READ_CODE');
writeToRead.forEach(r => {
  console.log(`[${r.id}] ${r.input}`);
  console.log(`  kw_hits=${JSON.stringify(r.keyword_hits)} rules=${JSON.stringify(r.calibration_rules)}`);
});

// tier distribution for READ_CODE
console.log('\n=== READ_CODE tier distribution ===');
const tierDist = {};
readCode.forEach(r => {
  tierDist[r.tier] = (tierDist[r.tier] || 0) + 1;
});
console.log(tierDist);

console.log('\n=== READ_CODE → CHAT_QA tier distribution ===');
const tierDist2 = {};
toChatQA.forEach(r => {
  tierDist2[r.tier] = (tierDist2[r.tier] || 0) + 1;
});
console.log(tierDist2);