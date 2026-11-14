import os
import sys
import tempfile

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
os.environ.setdefault("DATA_DIR", tempfile.mkdtemp())
os.environ["LLM_PROVIDER"] = "none"


@pytest.fixture()
def fresh_db(tmp_path):
    from app import db
    db.reset_conn_for_tests(str(tmp_path / "t.db"))
    db.init_db()
    yield db
