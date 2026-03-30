import { useEffect, useMemo, useRef, useState } from 'react'
import { marked } from 'marked'
import createDOMPurify from 'dompurify'
import './App.css'

const STAGES = [
  { stage: 'VALIDATING_INPUT', label: '입력 검증' },
  { stage: 'COLLECTING_FILES', label: 'GitHub 파일 수집' },
  { stage: 'STATIC_ANALYSIS', label: '정적 분석' },
  { stage: 'LLM_GENERATION', label: '기술 문서 생성' },
  { stage: 'FINALIZING', label: '결과 정리' },
]

function makeJobId() {
  return `job_${Math.random().toString(16).slice(2, 10)}`
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
  const [readmeText, setReadmeText] = useState('')
  const domPurifyRef = useRef(null)
  const [job, setJob] = useState({
    status: 'idle', // idle | running | done | failed
    jobId: null,
    stage: null,
    stageProgress: 0,
    error: null,
    result: null,
  })

  const intervalRef = useRef(null)

  const currentStageIndex = useMemo(() => {
    if (!job.stage) return -1
    return STAGES.findIndex((s) => s.stage === job.stage)
  }, [job.stage])

  const progressBarWidth = `${clampInt(job.stageProgress, 0, 100)}%`

  useEffect(() => {
    // browser 환경에서만 sanitizer 인스턴스를 생성합니다.
    domPurifyRef.current = createDOMPurify(window)
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current)
    }
  }, [])

  function resetJob() {
    if (intervalRef.current) clearInterval(intervalRef.current)
    intervalRef.current = null
    setJob({
      status: 'idle',
      jobId: null,
      stage: null,
      stageProgress: 0,
      error: null,
      result: null,
    })
    setReadmeText('')
  }

  function startMockJob() {
    const trimmed = githubUrl.trim()
    if (!isProbablyGitHubRepoUrl(trimmed)) {
      setJob((prev) => ({
        ...prev,
        status: 'failed',
        error: 'GitHub 저장소 URL을 확인해주세요. 예: https://github.com/{owner}/{repo}',
      }))
      return
    }

    if (intervalRef.current) clearInterval(intervalRef.current)

    const jobId = makeJobId()
    const stagePlan = [
      { stage: 'VALIDATING_INPUT', ms: 1200 },
      { stage: 'COLLECTING_FILES', ms: 2400 },
      { stage: 'STATIC_ANALYSIS', ms: 3200 },
      { stage: 'LLM_GENERATION', ms: 4200 },
      { stage: 'FINALIZING', ms: 1600 },
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
    const tickMs = 100

    intervalRef.current = setInterval(() => {
      const now = Date.now()
      const stage = stagePlan[stageIdx]

      const stageElapsed =
        now -
        (startedAt +
          stagePlan
            .slice(0, stageIdx)
            .reduce((acc, s) => acc + s.ms, 0))

      const stageProgress = clampInt(Math.round((stageElapsed / stage.ms) * 100), 0, 100)

      if (stageProgress >= 100) {
        stageIdx += 1
        if (stageIdx >= stagePlan.length) {
          if (intervalRef.current) clearInterval(intervalRef.current)
          intervalRef.current = null
          setJob((prev) => ({
            ...prev,
            status: 'done',
            stage: stagePlan[stagePlan.length - 1].stage,
            stageProgress: 100,
            result: {
              markdown: `# 기술 문서(목업 결과)\n\n- 입력: ${trimmed}\n- 분석 범위: 프론트/백엔드 유기적 연결(예정)\n\n## 다음에 붙일 것\n- 정적 분석 그래프(JSON)\n- 엔드포인트/의존성 맵\n- LLM 문서 생성 결과`,
              graph: { nodes: [], edges: [] },
            },
          }))
          return
        }

        setJob((prev) => ({
          ...prev,
          stage: stagePlan[stageIdx].stage,
          stageProgress: 0,
          error: null,
        }))
        return
      }

      setJob((prev) => ({
        ...prev,
        stage: stage.stage,
        stageProgress,
      }))
    }, tickMs)
  }

  const canStart =
    githubUrl.trim().length > 0 && isProbablyGitHubRepoUrl(githubUrl) && job.status !== 'running'

  useEffect(() => {
    if (job.status === 'done' && job.result?.markdown) {
      setReadmeText(job.result.markdown)
    }
  }, [job.status, job.result?.markdown])

  const readmeHtml = useMemo(() => {
    const md = readmeText ?? ''
    const rawHtml = marked.parse(md, { gfm: true, breaks: true })
    // LLM/유저 입력을 렌더링하므로 기본 XSS 방어를 위해 sanitize
    const purifier = domPurifyRef.current
    return purifier ? purifier.sanitize(rawHtml) : rawHtml
  }, [readmeText])

  async function copyReadme() {
    try {
      await navigator.clipboard.writeText(readmeText)
    } catch (e) {
      // 일부 환경에서 clipboard API가 막혀있을 수 있어 fallback 제공
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
        <div className="brand">
          <div className="logoMark" aria-hidden="true" />
          <div>
            <div className="brandName">AutoReadMe</div>
            <div className="brandSub">정적 코드 분석 + LLM 문서 생성</div>
          </div>
        </div>
        {job.status !== 'idle' ? (
          <button className="btn btnGhost" onClick={resetJob} type="button">
            초기화
          </button>
        ) : null}
      </header>

      <main className="appMain">
        {job.status === 'idle' ? (
          <section className="card heroCard">
            <h1 className="pageTitle">GitHub 저장소를 넣으면, 기술 문서를 자동 생성합니다</h1>
            <p className="pageDesc">
              진행률은 단계(stage)로 표시하고, 실패 시 재시도 UX를 지원하는 형태로 구성합니다.
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
              <button className="btn btnPrimary" onClick={startMockJob} disabled={!canStart} type="button">
                분석 시작
              </button>
            </div>

            <div className="helperRow">
              <div className="helperItem">
                <span className="helperDot" aria-hidden="true" />
                <span>단계별 진행률: `stage + stageProgress`(0~100)</span>
              </div>
              <div className="helperItem">
                <span className="helperDot helperDot2" aria-hidden="true" />
                <span>재시도/실패 표시를 위한 상태 머신 기반 UX</span>
              </div>
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
                    <>
                      Job: <span className="mono">{job.jobId}</span>
                    </>
                  ) : null}
                </div>
              </div>
              <div className="stageRight">
                {job.stage ? (
                  <>
                    <div className="stageName">
                      {STAGES[currentStageIndex]?.label ?? job.stage}
                    </div>
                    <div className="stageProgress">{job.stageProgress}%</div>
                  </>
                ) : null}
              </div>
            </div>

            <div
              className="progressTrack"
              role="progressbar"
              aria-valuemin="0"
              aria-valuemax="100"
              aria-valuenow={job.stageProgress}
            >
              <div className="progressFill" style={{ width: progressBarWidth }} />
            </div>

            <div className="stepper" aria-label="분석 단계">
              {STAGES.map((s, idx) => {
                const state =
                  idx < currentStageIndex ? 'done' : idx === currentStageIndex ? 'active' : 'todo'

                return (
                  <div key={s.stage} className={`step ${state}`}>
                    <div className="stepDot" aria-hidden="true" />
                    <div className="stepLabel">{s.label}</div>
                  </div>
                )
              })}
            </div>

            <div className="runningHint">
              백엔드는 아직 엔드포인트가 없어서, 현재 화면은 “목업(jobId/진행률)”으로 동작합니다.
              실제로 붙일 때는 <code>POST /api/analyze</code>로 시작하고, <code>GET /api/analyze/{'{jobId}'}</code> 를 폴링/대기해
              이 UI 상태를 갱신하면 됩니다.
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
              <button className="btn btnPrimary" onClick={startMockJob} type="button" disabled={!canStart}>
                다시 시도(목업)
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

                <div className="resultSectionTitle resultSectionTitleMinor">연결 그래프(예정)</div>
                <div className="graphPlaceholder">
                  노드/엣지를 JSON으로 받아서 인터랙티브하게 시각화하면
                  “유기적 연결 구조”를 문서와 함께 보여줄 수 있습니다.
                </div>

                <div className="actionsRow actionsRowTight">
                  <button
                    className="btn btnPrimary"
                    onClick={() => {
                      resetJob()
                      setGithubUrl(githubUrl)
                    }}
                    type="button"
                  >
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
