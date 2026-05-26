import { useEffect, useMemo, useRef, useState } from 'react'
import { marked } from 'marked'
import createDOMPurify from 'dompurify'
import mermaid from 'mermaid'
import './App.css'

mermaid.initialize({
  startOnLoad: false,
  theme: 'dark',
  securityLevel: 'loose',
  fontFamily: 'Inter, system-ui, sans-serif',
})

function Mermaid({ chart }) {
  const ref = useRef(null)

  useEffect(() => {
    let isMounted = true
    if (ref.current && chart) {
      const id = `mermaid-${Math.random().toString(36).substring(2, 11)}`
      mermaid
        .render(id, chart)
        .then(({ svg }) => {
          if (isMounted && ref.current) {
            ref.current.innerHTML = svg
          }
        })
        .catch((err) => {
          console.error('Mermaid render error:', err)
        })
    }
    return () => {
      isMounted = false
    }
  }, [chart])

  return <div className="mermaidContainer" ref={ref} />
}

function convertToMermaid(graph) {
  if (!graph || !graph.nodes || graph.nodes.length === 0) return ''
  let str = 'graph LR\n' 
  
  graph.nodes.forEach((node) => {
    if (node.type === 'repo') {
      str += `  ${node.id}(("${node.label}"))\n`
    } else if (node.type === 'stack') {
      str += `  ${node.id}{{"${node.label}"}}\n`
    } else if (node.type === 'endpoint') {
      str += `  ${node.id}["${node.label}"]\n`
    } else if (node.type === 'entity') {
      str += `  ${node.id}[("${node.label}")]\n`
    } else {
      str += `  ${node.id}["${node.label}"]\n`
    }
  })

  graph.edges.forEach((edge) => {
    str += `  ${edge.source} -->|${edge.type}| ${edge.target}\n`
  })

  str += '  classDef repo fill:#487bff,stroke:#fff,stroke-width:2px,color:#fff;\n'
  str += '  classDef stack fill:#8b5dff,stroke:#fff,stroke-width:1px,color:#fff;\n'
  str += '  classDef endpoint fill:#10b981,stroke:#fff,stroke-width:1px,color:#fff;\n'
  str += '  classDef entity fill:#f59e0b,stroke:#fff,stroke-width:1px,color:#fff;\n'
  
  graph.nodes.forEach((node) => {
    if (node.type) {
      str += `  class ${node.id} ${node.type};\n`
    }
  })

  return str
}

const STAGES = [
  { stage: 'VALIDATING', label: '입력 검증' },
  { stage: 'COLLECTING_FILES', label: 'GitHub 파일 수집' },
  { stage: 'DETECTING_STACK', label: '기술 스택 분석' },
  { stage: 'PROFILING_CODE', label: '코드 구조 분석' },
  { stage: 'BUILDING_CONTEXT', label: '컨텍스트 구성' },
  { stage: 'GENERATING_README', label: 'README 생성' },
  { stage: 'COMPLETED', label: '완료' },
]

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')
const MOCK_FLAG = import.meta.env.VITE_USE_MOCK_API
const USE_MOCK_API = MOCK_FLAG === 'true'
const POLLING_INTERVAL_MS = 1000

function normalizeFetchError(err, fallback) {
  const raw = typeof err?.message === 'string' ? err.message.trim() : ''
  if (
    /^load failed$/i.test(raw) ||
    /^failed to fetch$/i.test(raw) ||
    /networkerror/i.test(raw) ||
    /network request failed/i.test(raw)
  ) {
    return `백엔드에 연결할 수 없습니다. (${API_BASE_URL}) 서버가 실행 중인지 확인해 주세요.`
  }
  return raw || fallback
}

const COLOR_SCHEME_STORAGE_KEY = 'autoreadme-color-scheme'

function readStoredColorScheme() {
  try {
    const v = localStorage.getItem(COLOR_SCHEME_STORAGE_KEY)
    if (v === 'light' || v === 'dark') return v
  } catch { /* ignore */ }
  return 'dark'
}

function makeIdleJobState() {
  return {
    status: 'idle', 
    jobId: null,
    stage: null,
    stageProgress: 0,
    error: null,
    result: null,
  }
}

function clampInt(n, min, max) {
  return Math.max(min, Math.min(max, n))
}

function isProbablyGitHubRepoUrl(value) {
  const v = value.trim()
  return v.startsWith('https://github.com/') || v.startsWith('http://github.com/')
}

export default function App() {
  const [githubUrl, setGithubUrl] = useState('')
  const [projectDescription, setProjectDescription] = useState('')
  const [readmeText, setReadmeText] = useState('')
  const domPurifyRef = useRef(null)
  const [colorScheme, setColorScheme] = useState(readStoredColorScheme)
  const [job, setJob] = useState(makeIdleJobState)

  const intervalRef = useRef(null)

  const currentStageIndex = useMemo(() => {
    if (!job.stage) return -1
    return STAGES.findIndex((s) => s.stage === job.stage)
  }, [job.stage])

  const progressBarWidth = `${clampInt(job.stageProgress, 0, 100)}%`

  useEffect(() => {
    domPurifyRef.current = createDOMPurify(window)
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current)
    }
  }, [])

  useEffect(() => {
    const root = document.documentElement
    root.setAttribute('data-theme', colorScheme)
    try {
      localStorage.setItem(COLOR_SCHEME_STORAGE_KEY, colorScheme)
    } catch { /* ignore */ }
  }, [colorScheme])

  function clearPolling() {
    if (intervalRef.current) clearInterval(intervalRef.current)
    intervalRef.current = null
  }

  function resetJob() {
    clearPolling()
    setJob(makeIdleJobState())
    setReadmeText('')
  }

  async function parseErrorMessage(res, fallbackMessage) {
    try {
      const data = await res.json()
      return data?.error || fallbackMessage
    } catch {
      return fallbackMessage
    }
  }

  function startPollingJobStatus(jobId) {
    clearPolling()

    intervalRef.current = setInterval(async () => {
      try {
        const res = await fetch(`${API_BASE_URL}/api/analyze/${encodeURIComponent(jobId)}`)
        if (!res.ok) {
          const msg = await parseErrorMessage(res, '상태 조회에 실패했습니다.')
          throw new Error(msg)
        }

        const data = await res.json()
        setJob({
          status: data.status ?? 'failed',
          jobId: data.jobId ?? jobId,
          stage: data.stage ?? null,
          stageProgress: clampInt(data.stageProgress ?? 0, 0, 100),
          error: data.error ?? null,
          result: data.result ?? null,
        })

        if (data.status === 'done' || data.status === 'failed') {
          clearPolling()
        }
      } catch (e) {
        clearPolling()
        setJob((prev) => ({
          ...prev,
          status: 'failed',
          error: normalizeFetchError(e, '상태 조회 중 오류가 발생했습니다.'),
        }))
      }
    }, POLLING_INTERVAL_MS)
  }

  function startMockJob(trimmedUrl) {
    clearPolling()
    const jobId = `mock_${Math.random().toString(16).slice(2, 10)}`
    const stagePlan = [
      { stage: 'VALIDATING', ms: 800 },
      { stage: 'COLLECTING_FILES', ms: 1600 },
      { stage: 'DETECTING_STACK', ms: 1400 },
      { stage: 'PROFILING_CODE', ms: 1600 },
      { stage: 'BUILDING_CONTEXT', ms: 1000 },
      { stage: 'GENERATING_README', ms: 2200 },
      { stage: 'COMPLETED', ms: 900 },
    ]

    setJob({
      status: 'running',
      jobId,
      stage: stagePlan[0].stage,
      stageProgress: 0,
      error: null,
      result: null,
    })

    let stageIdx = 0
    const startedAt = Date.now()

    intervalRef.current = setInterval(() => {
      const now = Date.now()
      const elapsedBeforeCurrent = stagePlan.slice(0, stageIdx).reduce((acc, s) => acc + s.ms, 0)
      const current = stagePlan[stageIdx]
      const stageElapsed = now - (startedAt + elapsedBeforeCurrent)
      const stageProgress = clampInt(Math.round((stageElapsed / current.ms) * 100), 0, 100)

      if (stageProgress >= 100) {
        stageIdx += 1
        if (stageIdx >= stagePlan.length) {
          clearPolling()
          setJob((prev) => ({
            ...prev,
            status: 'done',
            stage: 'COMPLETED',
            stageProgress: 100,
            result: {
              markdown: `# 기술 문서(프론트 목업)\n\n- 입력 URL: ${trimmedUrl}\n- 실행 모드: Mock (백엔드 미연결)\n\n## 분석 요약\n- 단계 진행 UI 점검 완료\n- README 편집/복사/다운로드 동작 가능\n\n## 다음 단계\n- 실 API 연동 시 \`VITE_USE_MOCK_API=false\` 설정`,
              graph: { nodes: [], edges: [] },
            },
          }))
          return
        }
        setJob((prev) => ({ ...prev, stage: stagePlan[stageIdx].stage, stageProgress: 0 }))
        return
      }
      setJob((prev) => ({ ...prev, stage: current.stage, stageProgress }))
    }, 100)
  }

  async function startAnalyzeJob() {
    const trimmed = githubUrl.trim()
    if (!isProbablyGitHubRepoUrl(trimmed)) {
      setJob((prev) => ({
        ...prev,
        status: 'failed',
        error: 'GitHub 저장소 URL을 확인해주세요. 예: https://github.com/{owner}/{repo}',
      }))
      return
    }

    if (USE_MOCK_API) {
      startMockJob(trimmed)
      return
    }

    clearPolling()
    setJob({
      status: 'running',
      jobId: null,
      stage: 'VALIDATING',
      stageProgress: 0,
      error: null,
      result: null,
    })

    try {
      const res = await fetch(`${API_BASE_URL}/api/analyze`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          githubUrl: trimmed,
          projectDescription: projectDescription.trim() || undefined,
        }),
      })

      if (!res.ok) {
        const msg = await parseErrorMessage(res, '분석 시작 요청에 실패했습니다.')
        throw new Error(msg)
      }

      const data = await res.json()
      const jobId = data?.jobId
      if (!jobId) throw new Error('백엔드 응답에 jobId가 없습니다.')

      setJob((prev) => ({ ...prev, jobId, status: 'running' }))
      startPollingJobStatus(jobId)
    } catch (e) {
      setJob((prev) => ({
        ...prev,
        status: 'failed',
        error: normalizeFetchError(e, '분석 시작 중 오류가 발생했습니다.'),
      }))
    }
  }

  const canStart = isProbablyGitHubRepoUrl(githubUrl) && job.status !== 'running'

  useEffect(() => {
    if (job.status === 'done' && job.result?.markdown) {
      setReadmeText(job.result.markdown)
    }
  }, [job.status, job.result?.markdown])

  const readmeHtml = useMemo(() => {
    const md = readmeText ?? ''
    const rawHtml = marked.parse(md, { gfm: true, breaks: true })
    const purifier = domPurifyRef.current
    return purifier ? purifier.sanitize(rawHtml) : rawHtml
  }, [readmeText])

  async function copyReadme() {
    try {
      await navigator.clipboard.writeText(readmeText)
    } catch (e) {
      const ta = document.createElement('textarea')
      ta.value = readmeText
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      document.body.appendChild(ta)
      ta.focus()
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
  }

  function downloadReadme() {
    const blob = new Blob([readmeText], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'README.md'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  return (
    <div className="appShell">
      <header className="appHeader">
        <div className="appHeaderBar">
          <div className="themeToggle" role="group" aria-label="화면 테마">
            <button
              type="button"
              className={`themeToggleBtn ${colorScheme === 'light' ? 'themeToggleBtnActive' : ''}`}
              onClick={() => setColorScheme('light')}
              aria-pressed={colorScheme === 'light'}
            >
              라이트
            </button>
            <button
              type="button"
              className={`themeToggleBtn ${colorScheme === 'dark' ? 'themeToggleBtnActive' : ''}`}
              onClick={() => setColorScheme('dark')}
              aria-pressed={colorScheme === 'dark'}
            >
              다크
            </button>
          </div>
          {job.status !== 'idle' ? (
            <button className="btn btnGhost" onClick={resetJob} type="button">
              초기화
            </button>
          ) : null}
        </div>
        <h1 className="appTitleMark">AutoReadMe</h1>
      </header>

      <main className="appMain">
        {job.status === 'idle' ? (
          <section className="card heroCard">
            <h1 className="pageTitle">GitHub 저장소를 넣으면, 기술 문서를 자동 생성합니다</h1>
            <p className="pageDesc">
              프로젝트 코드 분석을 통해 고품질의 README.md를 자동으로 만들어 드립니다.
            </p>

            <div className="formRow">
              <label className="label">
                GitHub URL
                <input
                  className="input"
                  value={githubUrl}
                  onChange={(e) => setGithubUrl(e.target.value)}
                  placeholder="https://github.com/{owner}/{repo}"
                  spellCheck={false}
                  autoComplete="off"
                />
              </label>
            </div>

            <div className="formRow">
              <label className="label">
                프로젝트 설명 (선택)
                <textarea
                  className="input textarea"
                  style={{ minHeight: '80px', paddingTop: '8px' }}
                  value={projectDescription}
                  onChange={(e) => setProjectDescription(e.target.value)}
                  placeholder="이 저장소에 대한 핵심 설명이나 특징을 적어주세요. README 생성에 활용됩니다."
                  spellCheck={false}
                />
              </label>
            </div>

            <div className="formRow">
              <button className="btn btnPrimary" style={{ width: '100%' }} onClick={startAnalyzeJob} disabled={!canStart} type="button">
                분석 시작
              </button>
            </div>
          </section>
        ) : null}

        {job.status === 'running' ? (
          <section className="card">
            <div className="cardHeader">
              <div>
                <div className="cardTitle">분석 진행 중</div>
                <div className="cardSub">
                  {job.jobId ? (
                    <>Job: <span className="mono">{job.jobId}</span></>
                  ) : null}
                </div>
              </div>
              <div className="stageRight">
                {job.stage ? (
                  <>
                    <div className="stageName">
                      {STAGES.find(s => s.stage === job.stage)?.label ?? job.stage}
                    </div>
                    <div className="stageProgress">{job.stageProgress}%</div>
                  </>
                ) : null}
              </div>
            </div>

            <div className="progressTrack" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={job.stageProgress}>
              <div className="progressFill" style={{ width: progressBarWidth }} />
            </div>

            <div className="stepper" aria-label="분석 단계">
              {STAGES.map((s, idx) => {
                const state = idx < currentStageIndex ? 'done' : idx === currentStageIndex ? 'active' : 'todo'
                return (
                  <div key={s.stage} className={`step ${state}`}>
                    <div className="stepDot" aria-hidden="true" />
                    <div className="stepLabel">{s.label}</div>
                  </div>
                )
              })}
            </div>
          </section>
        ) : null}

        {job.status === 'failed' ? (
          <section className="card">
            <div className="cardHeader">
              <div>
                <div className="cardTitle cardTitleDanger">분석 실패</div>
                <div className="cardSub">에러 내용을 확인하고 다시 시도하세요.</div>
              </div>
            </div>
            <div className="errorBox">
              <div className="errorText">{job.error ?? '알 수 없는 오류'}</div>
            </div>
            <div className="actionsRow">
              <button className="btn btnPrimary" onClick={startAnalyzeJob} type="button" disabled={!canStart}>
                다시 시도
              </button>
              <button className="btn btnGhost" onClick={resetJob} type="button">
                다른 URL 입력
              </button>
            </div>
          </section>
        ) : null}

        {job.status === 'done' ? (
          <section className="card">
            <div className="cardHeader">
              <div>
                <div className="cardTitle">결과</div>
                <div className="cardSub">
                  Job: <span className="mono">{job.jobId}</span>
                </div>
              </div>
              <div className="stageRight">
                <div className="stageName">완료</div>
                <div className="stageProgress">100%</div>
              </div>
            </div>

            <div className="readmeLayout">
              <div className="readmePane">
                <div className="resultSectionTitle">README 편집(인라인)</div>
                <textarea
                  className="readmeTextarea"
                  value={readmeText}
                  onChange={(e) => setReadmeText(e.target.value)}
                  spellCheck={false}
                />
                <div className="actionsRow actionsRowTight">
                  <button className="btn btnPrimary" onClick={copyReadme} type="button">
                    README 복사
                  </button>
                  <button className="btn btnGhost" onClick={downloadReadme} type="button">
                    .md 다운로드
                  </button>
                </div>
              </div>

              <div className="readmePane">
                <div className="resultSectionTitle">README 미리보기</div>
                <div className="readmePreview" dangerouslySetInnerHTML={{ __html: readmeHtml }} />

                <div className="resultSectionTitle resultSectionTitleMinor">연결 그래프</div>
                {job.result?.graph?.nodes?.length > 0 ? (
                  <Mermaid chart={convertToMermaid(job.result.graph)} />
                ) : (
                  <div className="graphPlaceholder">
                    식별된 구성 요소 간의 관계가 없습니다.
                  </div>
                )}

                <div className="actionsRow actionsRowTight">
                  <button className="btn btnPrimary" onClick={resetJob} type="button">
                    새 분석
                  </button>
                </div>
              </div>
            </div>
          </section>
        ) : null}
      </main>
    </div>
  )
}
