const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, 'results', 'intent-per-row-test-20260824-213622.jsonl');
const lines = fs.readFileSync(file, 'utf8').split('\n').filter(Boolean);

const rows = lines.map(l => JSON.parse(l));

// Overall confusion matrix
const confusion = {};
for (const r of rows) {
  const k = `${r.gold_label}->${r.pred_label}`;
  confusion[k] = (confusion[k] || 0) + 1;
}

console.log('=== Total samples ===', rows.length);
console.log('\n=== Overall confusion (gold -> pred) ===');
Object.entries(confusion)
  .sort((a, b) => b[1] - a[1])
  .forEach(([k, v]) => console.log(`  ${k.padEnd(40)} ${v}`));

// READ_CODE detail
const readCode = rows.filter(r => r.gold_label === 'READ_CODE');
console.log('\n=== READ_CODE gold samples ===', readCode.length);
console.log('id'.padEnd(8), 'gold', 'pred', 'conf', 'tier', 'fb', 'kw_sug', 'kw_hits', 'slot_val');
console.log('-'.repeat(160));
readCode.forEach(r => {
  const kw = (r.keyword_hits || []).join('|');
  console.log(
    r.id.padEnd(8),
    r.gold_label.padEnd(12),
    r.pred_label.padEnd(12),
    String(r.pred_conf).slice(0, 6).padEnd(7),
    r.tier.padEnd(8),
    String(r.fallback).padEnd(4),
    (r.keyword_suggested || 'null').padEnd(12),
    kw.padEnd(40),
    r.slot_validation_outcome
  );
  console.log('  INPUT:', r.input);
});

// READ_CODE accuracy
const correct = readCode.filter(r => r.pred_label === 'READ_CODE').length;
console.log(`\nREAD_CODE accuracy: ${correct}/${readCode.length} = ${(correct/readCode.length*100).toFixed(1)}%`);

// Mis-classified READ_CODE samples
console.log('\n=== READ_CODE mis-classified ===');
readCode.filter(r => r.pred_label !== 'READ_CODE').forEach(r => {
  console.log(`\n[${r.id}] input: ${r.input}`);
  console.log(`  pred: ${r.pred_label} (conf=${r.pred_conf}) tier=${r.tier} fallback=${r.fallback}`);
  console.log(`  reason: ${r.fallback_reason || '-'}`);
  console.log(`  keyword_suggested: ${r.keyword_suggested}, keyword_score: ${r.keyword_score}`);
  console.log(`  keyword_hits: ${JSON.stringify(r.keyword_hits)}`);
  console.log(`  slot_score: ${r.slot_score}, slot_outcome: ${r.slot_validation_outcome}`);
  console.log(`  raw_llm_conf: ${r.raw_llm_conf}, calibration: ${JSON.stringify(r.calibration_rules)}`);
});

// Patterns of misclassification
console.log('\n=== Patterns ===');
const mis = readCode.filter(r => r.pred_label !== 'READ_CODE');
console.log('mis-classified:', mis.length);
console.log('  -> CHAT_QA:', mis.filter(r => r.pred_label === 'CHAT_QA').length);
console.log('  -> OFF_TOPIC:', mis.filter(r => r.pred_label === 'OFF_TOPIC').length);
console.log('  -> WRITE_PROJECT:', mis.filter(r => r.pred_label === 'WRITE_PROJECT').length);
console.log('  -> other:', mis.filter(r => !['CHAT_QA', 'OFF_TOPIC', 'WRITE_PROJECT'].includes(r.pred_label)).length);

// Investigate keyword hints for mis-classified
console.log('\n=== Keyword hits in mis-classified READ_CODE ===');
mis.forEach(r => {
  console.log(`  [${r.id}] (pred=${r.pred_label}) hits=${JSON.stringify(r.keyword_hits)} sugg=${r.keyword_suggested} score=${r.keyword_score}`);
});

// CHAT_QA -> READ_CODE (false positives)
console.log('\n=== CHAT_QA gold -> READ_CODE pred (false positives) ===');
rows.filter(r => r.gold_label === 'CHAT_QA' && r.pred_label === 'READ_CODE').forEach(r => {
  console.log(`  [${r.id}] input: ${r.input}`);
  console.log(`    hits=${JSON.stringify(r.keyword_hits)} sugg=${r.keyword_suggested}`);
});