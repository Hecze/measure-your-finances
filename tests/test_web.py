from fastapi.testclient import TestClient


def test_pantallas_responden(fresh_db):
    from app.main import app
    from app import ingest
    from .test_ingest import ROWS, make_yape_excel
    from app.yape_excel import parse_yape_excel
    ingest.import_yape_rows(parse_yape_excel(make_yape_excel(ROWS)))
    with TestClient(app) as c:
        for url in ["/", "/?m=2026-10", "/pendientes", "/lista?m=2026-10", "/lista?m=2026-10&c=none", "/importar",
                    "/ajustes", "/ajustes?s=correo", "/ajustes?s=ia", "/categorias", "/correos", "/reglas"]:
            assert c.get(url).status_code == 200, url
        tx = fresh_db.query_one("SELECT id FROM transactions WHERE counterparty='Jose Ram*'")["id"]
        r = c.post(f"/api/tx/{tx}", json={"status": "excluded", "exclude_reason": "Pase de dinero / encargo"})
        assert r.json()["tx"]["status"] == "excluded"


def test_subir_excel_con_vista_previa(fresh_db):
    from app.main import app
    from .test_ingest import ROWS, make_yape_excel
    with TestClient(app) as c:
        r = c.post("/importar", files={"file": ("r.xlsx", make_yape_excel(ROWS))})
        assert r.status_code == 200 and "gastos nuevos" in r.text
        token = r.text.split('name="token" value="')[1].split('"')[0]
        r = c.post("/importar/confirmar", data={"token": token})
        assert "5 gastos nuevos" in r.text
