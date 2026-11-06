import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

const stages = [
  {
    title: "Microservices",
    short: "Instrumented services",
    input: "Application requests and service metadata",
    output: "Traces, metrics, and structured logs",
    description: "Start with the services that produce the operational signals."
  },
  {
    title: "Collection",
    short: "OpenTelemetry",
    input: "OTLP metrics, traces, and logs",
    output: "Processed and routed telemetry",
    description: "The configured OpenTelemetry Collector receives and routes telemetry."
  },
  {
    title: "Signal stores",
    short: "Metrics · logs · traces",
    input: "Collected telemetry",
    output: "Prometheus metrics, Loki logs, Jaeger traces",
    description: "Backend integrations query the configured stores for investigation evidence."
  },
  {
    title: "AI analysis",
    short: "Anomaly · forecast",
    input: "Available historical metric observations",
    output: "Anomaly results and degradation forecasts",
    description: "The Python service returns model outputs and feature evidence when inputs are sufficient."
  },
  {
    title: "Investigation",
    short: "Ranked evidence",
    input: "Metrics, traces, logs, and service metadata",
    output: "Ranked candidate causes with supporting evidence",
    description: "Candidates are ranked for investigation; a ranking is not proof of causality."
  },
  {
    title: "Adaptive control",
    short: "Bounded sampling",
    input: "Fresh telemetry risk signals and safety policies",
    output: "Audited trace-sampling decisions",
    description: "The controller can adjust supported demo-service trace sampling within its configured limits."
  }
];

const capabilities = [
  {
    number: "01",
    title: "See service health in context",
    label: "REAL-TIME OBSERVABILITY",
    body: "Explore measured request rate, error rate, p95 latency, service metadata, logs, and traces. Values appear only when the connected telemetry source returns them.",
    visual: "signals"
  },
  {
    number: "02",
    title: "Spot degradation earlier",
    label: "PREDICTIVE MONITORING",
    body: "Evaluate a five-minute p95 latency degradation forecast from recent metric windows. Forecasts are model outputs—not guarantees—and model comparisons remain explicitly qualified.",
    visual: "forecast"
  },
  {
    number: "03",
    title: "Understand the evidence",
    label: "EXPLAINABLE ANALYSIS",
    body: "Inspect returned feature contributions alongside observed metrics, model versions, and limitations. Explanations are only shown when the analysis API supplies them.",
    visual: "explain"
  },
  {
    number: "04",
    title: "Investigate with dependencies",
    label: "ROOT-CAUSE RANKING",
    body: "Prioritise candidate services using metric deviations, service relationships, and relevant logs. The interface distinguishes an evidence ranking from confirmed causality.",
    visual: "causes"
  },
  {
    number: "05",
    title: "Adapt within clear limits",
    label: "ADAPTIVE TELEMETRY",
    body: "Review controller state, safety bounds, cooldowns, and an audit trail. The current controller changes demo-service trace sampling; Prometheus scrape intervals remain fixed.",
    visual: "adaptive"
  },
  {
    number: "06",
    title: "Follow an incident end to end",
    label: "INCIDENT INVESTIGATION",
    body: "Move from an incident or anomaly to the affected service and the available metric, trace, and log evidence without treating predictions as confirmed incidents.",
    visual: "incident"
  }
];

const demoServices = ["checkout-api", "catalog-service", "payment-service"];

function ProductIllustration({ type }: { type: string }) {
  if (type === "signals") {
    return (
      <div className="landing-visual signal-visual" aria-label="Illustration of service telemetry signals">
        <div className="visual-topline"><span>Service signals</span><span className="visual-tag">Illustration</span></div>
        <div className="visual-metric-row"><span>Request rate</span><strong>Measured from source</strong></div>
        <div className="visual-metric-row"><span>Latency · p95</span><strong>Measured from source</strong></div>
        <div className="visual-metric-row"><span>Error rate</span><strong>Measured from source</strong></div>
        <svg viewBox="0 0 440 100" role="img" aria-label="Decorative telemetry line illustration">
          <path d="M4 74 C36 68 42 46 72 52 S109 79 141 55 S181 34 211 47 S245 71 276 48 S320 25 347 42 S388 64 436 18" />
          <path className="visual-chart-secondary" d="M4 84 C48 78 71 72 109 75 S158 64 193 68 S246 55 281 62 S328 48 369 53 S407 43 436 47" />
        </svg>
        <p>Data is shown in the authenticated explorer when returned by the connected backend.</p>
      </div>
    );
  }
  if (type === "forecast") {
    return (
      <div className="landing-visual forecast-visual" aria-label="Illustration of a forecast horizon">
        <div className="visual-topline"><span>Forecast horizon</span><span className="visual-tag">Model output</span></div>
        <div className="forecast-chart">
          <div className="forecast-axis"><span>Observed</span><span>Future window</span></div>
          <div className="forecast-line"><span /><span /><span /><span /><i /></div>
          <div className="forecast-event">Degradation threshold</div>
        </div>
        <p>Illustrative chart. Forecast values are not live or evaluated results.</p>
      </div>
    );
  }
  if (type === "explain") {
    return (
      <div className="landing-visual explain-visual" aria-label="Illustration of feature contribution evidence">
        <div className="visual-topline"><span>Evidence returned</span><span className="visual-tag">Example layout</span></div>
        {["p95 latency", "Error rate", "Request rate"].map((item, index) => (
          <div className="contribution-row" key={item}>
            <span>{item}</span><div><i style={{ width: `${76 - index * 19}%` }} /></div><small>{index === 0 ? "+" : "−"} signal</small>
          </div>
        ))}
        <p>Bars illustrate layout only; actual contribution values come from the model response.</p>
      </div>
    );
  }
  if (type === "causes") {
    return (
      <div className="landing-visual cause-visual" aria-label="Illustration of ranked root-cause candidates">
        <div className="visual-topline"><span>Investigation candidates</span><span className="visual-tag">Not causal proof</span></div>
        {["checkout-api", "payment-service", "postgres"].map((service, index) => (
          <div className="cause-row" key={service}><b>0{index + 1}</b><span>{service}</span><small>{index === 0 ? "Metric + trace evidence" : "Evidence to inspect"}</small></div>
        ))}
        <p>Illustrative candidates, not an actual incident result.</p>
      </div>
    );
  }
  if (type === "adaptive") {
    return (
      <div className="landing-visual adaptive-visual" aria-label="Illustration of bounded adaptive sampling">
        <div className="visual-topline"><span>Sampling policy</span><span className="visual-tag">Safety-bounded</span></div>
        <div className="policy-track"><span className="policy-fill" /></div>
        <div className="policy-values"><span>Minimum rate</span><strong>Configured bounds</strong><span>Maximum rate</span></div>
        <div className="policy-audit"><span className="status-dot" />Decision recorded in controller audit history</div>
        <p>No resource-savings claim is implied by a sampling change.</p>
      </div>
    );
  }
  return (
    <div className="landing-visual incident-visual" aria-label="Illustration of an incident investigation timeline">
      <div className="visual-topline"><span>Investigation timeline</span><span className="visual-tag">Evidence-led</span></div>
      <div className="incident-steps"><span>Signal</span><i /><span>Service</span><i /><span>Evidence</span><i /><span>Review</span></div>
      <p>Follow related data where available; anomaly, forecast, and incident records remain distinct.</p>
    </div>
  );
}

function Showcase() {
  const [service, setService] = useState(demoServices[0]);
  const [panel, setPanel] = useState("Service map");
  const panels = ["Service map", "Trace", "Logs", "AI signals"];

  return (
    <section className="landing-section showcase-section landing-reveal" id="showcase">
      <div className="section-heading">
        <p className="landing-eyebrow">PRODUCT PREVIEW</p>
        <h2>One place to follow the evidence.</h2>
        <p>A clickable interface preview, kept separate from real backend operations.</p>
      </div>
      <div className="showcase-window">
        <div className="showcase-chrome"><div className="window-dots"><i /><i /><i /></div><strong>Aexa · Investigation workspace</strong><span className="demo-watermark">DEMO DATA · NOT LIVE</span></div>
        <div className="showcase-controls">
          <div className="showcase-tabs" role="tablist" aria-label="Preview panel">
            {panels.map((item) => <button key={item} role="tab" aria-selected={panel === item} className={panel === item ? "selected" : ""} onClick={() => setPanel(item)}>{item}</button>)}
          </div>
          <label>Service
            <select value={service} onChange={(event) => setService(event.currentTarget.value)}>
              {demoServices.map((item) => <option key={item}>{item}</option>)}
            </select>
          </label>
        </div>
        <div className="showcase-content">
          <aside className="showcase-service-list"><span>DEMO SERVICES</span>{demoServices.map((item) => <button key={item} className={item === service ? "active" : ""} onClick={() => setService(item)}><i />{item}</button>)}</aside>
          <div className="showcase-main">
            <div className="showcase-title"><div><small>{panel.toUpperCase()} · {service}</small><h3>{panel === "Service map" ? "Dependency overview" : panel === "Trace" ? "Distributed trace" : panel === "Logs" ? "Related log events" : "Analysis signals"}</h3></div><span className="demo-watermark">ILLUSTRATIVE</span></div>
            {panel === "Service map" ? (
              <div className="dependency-preview">
                <div className="dependency-node primary">{service}<small>Selected service</small></div><span className="dependency-link" /><div className="dependency-node">{service === "checkout-api" ? "payment-service" : "checkout-api"}<small>Dependency</small></div><span className="dependency-link" /><div className="dependency-node">postgres<small>Storage</small></div>
              </div>
            ) : panel === "Trace" ? (
              <div className="trace-preview">{[["gateway", 88], [service, 76], ["database query", 51], ["response", 68]].map(([label, width], index) => <div className="trace-preview-row" key={label}><span>{index ? "↳ " : ""}{label}</span><div><i style={{ width: `${width}%` }} /></div><small>{index + 12} ms</small></div>)}</div>
            ) : panel === "Logs" ? (
              <div className="log-preview">{["INFO  request received · trace_id=demo-01", "WARN  dependency latency elevated · demo", "INFO  response completed · trace_id=demo-01"].map((line) => <p key={line}>{line}</p>)}</div>
            ) : (
              <div className="signal-preview"><div><span>Forecast</span><strong>Requires live model response</strong></div><div><span>Root cause</span><strong>Candidate ranking only</strong></div><div><span>Explainability</span><strong>Model evidence when available</strong></div></div>
            )}
          </div>
        </div>
        <p className="showcase-note">This interactive preview uses illustrative content only. It does not call APIs or change collector settings.</p>
      </div>
    </section>
  );
}

export function LandingPage() {
  const { user } = useAuth();
  const [activeStage, setActiveStage] = useState(0);
  const stage = stages[activeStage];
  const productLink = user ? "/services" : "/login";

  useEffect(() => {
    const sections = Array.from(document.querySelectorAll<HTMLElement>(".landing-reveal"));
    const revealAll = () => sections.forEach((section) => section.classList.add("is-visible"));
    if (!("IntersectionObserver" in window) || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      revealAll();
      return;
    }

    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -36px 0px" });
    sections.forEach((section) => observer.observe(section));
    return () => observer.disconnect();
  }, []);

  return (
    <main className="landing-page">
      <header className="landing-nav">
        <Link className="landing-brand" to="/" aria-label="Aexa home"><span className="brand-mark" />Aexa<span>OBSERVABILITY</span></Link>
        <nav aria-label="Main navigation">
          <a href="#platform">Platform</a><a href="#workflow">How it works</a><a href="#use-cases">Use cases</a><a href="#architecture">Architecture</a>
        </nav>
        <div className="landing-nav-actions">
          <Link className="landing-login" to={user ? "/services" : "/login"}>{user ? "Open dashboard" : "Sign in"}</Link>
          <Link className="landing-nav-cta" to={productLink}>{user ? "Explore services" : "Explore platform"}<span aria-hidden="true">↗</span></Link>
        </div>
      </header>

      <section className="landing-hero">
        <div className="hero-glow" aria-hidden="true" />
        <div className="landing-hero-copy">
          <p className="landing-eyebrow"><span className="status-dot" />OBSERVABILITY, WITH CONTEXT</p>
          <h1>See the signal.<br /><em>Understand the system.</em></h1>
          <p className="landing-lede">Connect service telemetry to explainable analysis and evidence-led investigation—so engineering teams can understand what changed, where to look, and what the data actually supports.</p>
          <div className="landing-hero-actions"><Link className="landing-button primary" to={productLink}>{user ? "Open service explorer" : "Explore the platform"}<span>↗</span></Link><a className="landing-button secondary" href="#workflow">See how it works<span>↓</span></a></div>
          <p className="landing-note">Built for local development and controlled evaluation. No fabricated live metrics.</p>
        </div>
        <div className="hero-system-card" aria-label="Illustrative telemetry flow">
          <div className="hero-system-top"><span><i /> PLATFORM FLOW</span><span>ILLUSTRATION · NOT LIVE</span></div>
          <svg className="hero-flow-svg" viewBox="0 0 620 310" role="img" aria-label="Services send telemetry through collection into analysis and investigation">
            <defs><linearGradient id="flowGradient" x1="0" x2="1"><stop offset="0" stopColor="#80a9ff" /><stop offset="1" stopColor="#78d8ba" /></linearGradient></defs>
            <path className="flow-path" d="M110 100 C200 100 180 165 274 165 S360 96 450 100" /><path className="flow-path lower" d="M110 214 C200 214 190 165 274 165 S360 218 450 214" />
            <circle className="flow-particle particle-one" r="5"><animateMotion dur="5s" repeatCount="indefinite" path="M110 100 C200 100 180 165 274 165 S360 96 450 100" /></circle>
            <circle className="flow-particle particle-two" r="4"><animateMotion dur="6s" repeatCount="indefinite" path="M110 214 C200 214 190 165 274 165 S360 218 450 214" /></circle>
          </svg>
          <div className="hero-flow-nodes">
            <div className="hero-flow-node node-service"><b>01</b><strong>Services</strong><small>Application signals</small></div>
            <div className="hero-flow-node node-collector"><b>02</b><strong>Collector</strong><small>OpenTelemetry</small></div>
            <div className="hero-flow-node node-analysis"><b>03</b><strong>Analysis</strong><small>Model evidence</small></div>
            <div className="hero-flow-node node-investigate"><b>04</b><strong>Investigate</strong><small>Candidate causes</small></div>
          </div>
          <div className="hero-system-footer"><span>Metrics</span><span>Logs</span><span>Traces</span><span>Evidence</span></div>
        </div>
        <div className="hero-proof"><span>Designed around connected telemetry</span><span>Explanations backed by returned evidence</span><span>Adaptive controls with explicit safety limits</span></div>
      </section>

      <section className="technology-strip" aria-label="Verified technology stack">
        <p>TECHNOLOGIES CONFIGURED IN THIS PROJECT</p>
        <div>{["React", "Spring Boot", "Python · FastAPI", "OpenTelemetry", "PostgreSQL", "Prometheus", "Loki", "Jaeger", "Kafka"].map((name) => <span key={name}>{name}</span>)}</div>
      </section>

      <section className="landing-section workflow-section landing-reveal" id="workflow">
        <div className="section-heading">
          <p className="landing-eyebrow">THE OBSERVABILITY LOOP</p><h2>From service signals<br />to a clearer next step.</h2>
          <p>Choose a stage to inspect what enters the system, what it produces, and where the evidence goes next.</p>
        </div>
        <div className="workflow-layout">
          <div className="workflow-diagram">
            <svg viewBox="0 0 1000 130" preserveAspectRatio="none" aria-hidden="true"><path d="M80 65 H920" /><path d="M80 65 H920" className="workflow-progress" /></svg>
            {stages.map((item, index) => <button key={item.title} className={`workflow-node${activeStage === index ? " active" : ""}`} onClick={() => setActiveStage(index)} aria-pressed={activeStage === index}>
              <span className="workflow-node-index">0{index + 1}</span><strong>{item.title}</strong><small>{item.short}</small>
            </button>)}
          </div>
          <div className="workflow-mobile-list">{stages.map((item, index) => <button key={item.title} className={`workflow-mobile-step${activeStage === index ? " active" : ""}`} onClick={() => setActiveStage(index)} aria-pressed={activeStage === index}><span>0{index + 1}</span><strong>{item.title}</strong><small>{item.short}</small></button>)}</div>
          <article className="workflow-detail" aria-live="polite">
            <div><span className="detail-kicker">STAGE 0{activeStage + 1}</span><h3>{stage.title}</h3><p>{stage.description}</p></div>
            <div className="workflow-io"><div><small>INPUT</small><span>{stage.input}</span></div><b aria-hidden="true">→</b><div><small>OUTPUT</small><span>{stage.output}</span></div></div>
          </article>
        </div>
      </section>

      <section className="landing-section capability-section landing-reveal" id="platform">
        <div className="section-heading"><p className="landing-eyebrow">PLATFORM CAPABILITIES</p><h2>Made for real investigation,<br />not just another dashboard.</h2><p>Each capability reflects an existing application workflow, with limitations stated where evidence or integration is constrained.</p></div>
        <div className="capability-list">{capabilities.map((item, index) => <article className={`capability-row${index % 2 ? " reverse" : ""}`} key={item.number}>
          <div className="capability-copy"><span className="capability-number">{item.number}</span><p className="landing-eyebrow">{item.label}</p><h3>{item.title}</h3><p>{item.body}</p><Link to={item.number === "05" ? "/adaptive" : item.number === "04" ? "/investigate" : item.number === "02" ? "/predictions" : "/telemetry"}>Explore in the app <span>↗</span></Link></div>
          <ProductIllustration type={item.visual} />
        </article>)}</div>
      </section>

      <Showcase />

      <section className="landing-section how-section landing-reveal">
        <div className="section-heading"><p className="landing-eyebrow">HOW IT WORKS</p><h2>A practical path from data<br />to operational context.</h2></div>
        <div className="how-grid">{[
          ["01", "Collect", "Instrumented services emit telemetry through the configured collector."],
          ["02", "Correlate", "Backend integrations retrieve available metrics, logs, traces, and service metadata."],
          ["03", "Analyze", "Anomaly detection and forecasting process supported metric windows when inputs are sufficient."],
          ["04", "Rank", "Dependency-aware analysis prioritises candidate services using available evidence."],
          ["05", "Explain", "Model outputs, feature contributions, and limitations remain visible to the investigator."],
          ["06", "Adapt", "A deterministic controller can adjust supported trace sampling within safety limits."]
        ].map(([number, title, body]) => <article key={number}><span>{number}</span><h3>{title}</h3><p>{body}</p></article>)}</div>
      </section>

      <section className="architecture-section landing-reveal" id="architecture">
        <div className="architecture-copy"><p className="landing-eyebrow">SYSTEM ARCHITECTURE</p><h2>Composable layers.<br />Visible boundaries.</h2><p>The local project connects application telemetry to stores, model analysis, backend APIs, and the React investigation interface.</p><Link to={user ? "/services" : "/login"} className="architecture-link">Explore the application <span>↗</span></Link></div>
        <div className="architecture-stack">{[
          ["APPLICATION", "Instrumented microservices", "Demo workloads · OpenTelemetry SDKs"],
          ["COLLECTION", "OpenTelemetry Collector", "Receive · process · export"],
          ["STORAGE & STREAMING", "Prometheus · Loki · Jaeger · Kafka", "Metrics · logs · traces · configured pipeline"],
          ["ANALYSIS", "Python FastAPI ML service", "Anomaly · forecast · evidence ranking"],
          ["ORCHESTRATION", "Spring Boot · PostgreSQL", "REST APIs · authentication · persistence"],
          ["INVESTIGATION", "React dashboard", "Overview · telemetry · incidents · controls"]
        ].map(([label, title, description]) => <article key={label}><span>{label}</span><div><strong>{title}</strong><small>{description}</small></div><i aria-hidden="true">↓</i></article>)}</div>
      </section>

      <section className="landing-section use-cases-section landing-reveal" id="use-cases">
        <div className="section-heading"><p className="landing-eyebrow">OPERATIONAL SCENARIOS</p><h2>Built around the questions<br />engineers actually ask.</h2><p>Supported investigation paths—not promised outcomes or measured improvements.</p></div>
        <div className="use-case-grid">{[
          ["Slow service", "Inspect request latency, service traces, and related log events.", "Metric and trace evidence when telemetry is available."],
          ["Database slowdown", "Follow dependency spans and look for downstream latency changes.", "Dependency candidate ranking; causality is not confirmed."],
          ["Failure cascade", "Compare service errors and dependency relationships across a trace.", "Correlated telemetry from configured stores."],
          ["Error-rate increase", "Review measured error ratios alongside service logs and anomalies.", "Stored anomaly results where analysis has been run."],
          ["Resource pressure", "Use resource signals only when the instrumentation exports them.", "No resource panel is implied when data is unavailable."],
          ["Telemetry volume", "Review adaptive sampler state, decisions, and safety bounds.", "Sampling changes are not resource-savings measurements."]
        ].map(([title, problem, response]) => <article key={title}><span className="use-case-mark">↗</span><h3>{title}</h3><p>{problem}</p><small>{response}</small></article>)}</div>
      </section>

      <section className="landing-cta landing-reveal"><div><p className="landing-eyebrow">START WITH THE EVIDENCE</p><h2>Bring your system into focus.</h2><p>Open the authenticated workspace to inspect connected services and available telemetry.</p></div><Link to={user ? "/services" : "/login"} className="landing-button primary">{user ? "Open service explorer" : "Sign in to explore"}<span>↗</span></Link></section>
      <footer className="landing-footer"><Link className="landing-brand" to="/"><span className="brand-mark" />Aexa</Link><span>Adaptive explainable observability · Local project</span><div><a href="#workflow">How it works</a><Link to="/login">Sign in</Link><Link to="/settings">System settings</Link></div></footer>
    </main>
  );
}
