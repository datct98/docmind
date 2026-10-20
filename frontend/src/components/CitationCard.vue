<template>
  <div class="citation-card" :class="{ 'is-expanded': isExpanded }">
    <div class="citation-header" @click="isExpanded = !isExpanded">
      <div class="citation-meta">
        <span class="page-badge">
          <svg xmlns="http://www.w3.org/2000/svg" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/>
          </svg>
          Trang {{ citation.page }}
        </span>

        <span v-if="docName" class="doc-title" :title="docName">
          {{ docName }}
        </span>

        <span v-if="headingPathString" class="heading-path" :title="headingPathString">
          {{ headingPathString }}
        </span>
      </div>

      <div class="citation-actions">
        <button class="action-btn" :title="isCopied ? 'Đã sao chép' : 'Sao chép đoạn trích'" @click.stop="copySnippet">
          <svg v-if="!isCopied" xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>
            <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
          </svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#34d399" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
        </button>

        <span class="chevron-icon">
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :style="{ transform: isExpanded ? 'rotate(180deg)' : 'rotate(0deg)' }">
            <path d="m6 9 6 6 6-6"/>
          </svg>
        </span>
      </div>
    </div>

    <!-- Snippet Body -->
    <div v-show="isExpanded" class="citation-body">
      <blockquote class="snippet-quote">
        "{{ citation.snippet }}"
      </blockquote>

      <div class="citation-footer">
        <span class="chunk-tag">Chunk ID: {{ citation.chunk_id || 'N/A' }}</span>
        <span v-if="citation.bbox && citation.bbox.length" class="bbox-tag" title="Bounding Box Coordinates (x0, y0, x1, y1)">
          BBox: [{{ citation.bbox.map(n => Math.round(n)).join(', ') }}]
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  citation: {
    type: Object,
    required: true
  },
  docName: {
    type: String,
    default: ''
  }
})

const isExpanded = ref(true)
const isCopied = ref(false)

const headingPathString = computed(() => {
  if (Array.isArray(props.citation.heading_path) && props.citation.heading_path.length > 0) {
    return props.citation.heading_path.join(' › ')
  }
  return ''
})

const copySnippet = async () => {
  if (!props.citation.snippet) return
  try {
    await navigator.clipboard.writeText(props.citation.snippet)
    isCopied.value = true
    setTimeout(() => {
      isCopied.value = false
    }, 2000)
  } catch {
    // fallback
  }
}
</script>

<style scoped>
.citation-card {
  background: #FFFFFF;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  margin-top: 6px;
  overflow: hidden;
  transition: all 0.16s ease;
}

.citation-card:hover {
  border-color: #A8C7FA;
  background: #F8FAFD;
}

.citation-header {
  padding: 8px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
  gap: 8px;
}

.citation-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.page-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--accent-pill-bg);
  color: var(--accent-pill-text);
  border: 1px solid #A8C7FA;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 6px;
  white-space: nowrap;
}

.doc-title {
  font-size: 0.74rem;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 170px;
}

.heading-path {
  font-size: 0.7rem;
  color: var(--text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: var(--font-mono);
}

.citation-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.action-btn {
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  padding: 3px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  transition: all 0.15s ease;
}

.action-btn:hover {
  color: var(--text-main);
  background: #E9EEF6;
}

.chevron-icon {
  display: flex;
  align-items: center;
  color: var(--text-dim);
  transition: transform 0.2s ease;
}

.citation-body {
  padding: 0 12px 10px;
  border-top: 1px dashed var(--border-subtle);
}

.snippet-quote {
  font-size: 0.82rem;
  line-height: 1.5;
  color: var(--text-secondary);
  font-style: italic;
  margin: 8px 0 6px;
  padding-left: 10px;
  border-left: 2.5px solid var(--accent-primary);
}

.citation-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.66rem;
  font-family: var(--font-mono);
  color: var(--text-dim);
  margin-top: 4px;
}

.chunk-tag, .bbox-tag {
  background: #F0F4F9;
  color: var(--text-secondary);
  padding: 1px 6px;
  border-radius: 4px;
}
</style>
