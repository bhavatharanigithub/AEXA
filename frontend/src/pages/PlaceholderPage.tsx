type Props = {
  title: string;
  body: string;
};

export function PlaceholderPage({ title, body }: Props) {
  return (
    <>
      <div className="page-head">
        <h1>{title}</h1>
      </div>
      <div className="card">
        <p className="empty">{body}</p>
      </div>
    </>
  );
}
