import json
import os
from datetime import datetime

from flask import Flask, redirect, render_template_string, request, url_for

app = Flask(__name__)
NOTAS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "notas.json")

PAGE = """<!doctype html>
<html lang="es">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Mis notas</title>
  <style>
    body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
           max-width: 640px; margin: 2rem auto; padding: 0 1rem;
           color: #1a1a1a; background: #fff; line-height: 1.5; }
    h1 { font-size: 1.5rem; }
    form { margin-bottom: 1.5rem; }
    textarea { width: 100%; min-height: 80px; padding: .5rem;
               border: 1px solid #999; font: inherit; box-sizing: border-box; }
    button { margin-top: .5rem; padding: .5rem 1rem; font: inherit;
             background: #1a1a1a; color: #fff; border: none; cursor: pointer; }
    ul { list-style: none; padding: 0; }
    li { border-top: 1px solid #ddd; padding: .6rem 0; }
    small { color: #666; }
    .vacia { color: #666; }
  </style>
</head>
<body>
  <h1>Mis notas</h1>
  <form method="post" action="{{ url_for('agregar') }}">
    <textarea name="texto" placeholder="Escribe una nota..." required></textarea><br>
    <button type="submit">Guardar nota</button>
  </form>
  {% if notas %}
  <ul>
    {% for n in notas|reverse %}
    <li>{{ n.texto }}<br><small>{{ n.fecha }}</small></li>
    {% endfor %}
  </ul>
  {% else %}
  <p class="vacia">Aún no hay notas.</p>
  {% endif %}
</body>
</html>
"""


def leer_notas():
    if not os.path.exists(NOTAS_FILE):
        return []
    try:
        with open(NOTAS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError):
        return []


def guardar_notas(notas):
    with open(NOTAS_FILE, "w", encoding="utf-8") as f:
        json.dump(notas, f, ensure_ascii=False, indent=2)


@app.route("/", methods=["GET"])
def index():
    return render_template_string(PAGE, notas=leer_notas())


@app.route("/agregar", methods=["POST"])
def agregar():
    texto = request.form.get("texto", "").strip()
    if texto:
        notas = leer_notas()
        notas.append({
            "texto": texto,
            "fecha": datetime.now().strftime("%Y-%m-%d %H:%M"),
        })
        guardar_notas(notas)
    return redirect(url_for("index"))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
