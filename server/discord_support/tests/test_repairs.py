"""Repair eligibility, scope gates and an offline Codex proposal pipeline."""
import copy
import json
import subprocess
import time
import os
import sys

import pytest

from discord_support.fix_runner import Runner,check_policy,validate_patch
from discord_support.repairs import eligible


def report():
    return {"kind":"bug","summary":"Counter shows the wrong total","facts":{"platform":"windows","version":"1.3.0","distribution":"stable","build":"","expected":"2","actual":"0","reproduction":"Open counter and select one item"},
            "published_build":{"verified":True,"checked_at":int(time.time()),"source_commit":"a"*40,"source_has_uncommitted_changes":False,"manifest_sha256":"b"*64,"platforms":{"windows":{"version":"1.3.0","available":True}}}}


@pytest.mark.parametrize("kind",["feature","private_help","human"])
def test_only_reproduced_bug_reports_can_enter_automated_assessment(kind):
    data=report();data["kind"]=kind;assert not eligible(data)[0]


@pytest.mark.parametrize("reason",["bypass DRM","unlock premium","authentication fails","stolen stream","payment issue"])
def test_lawfulness_and_sensitive_domains_require_human_review(reason):
    data=report();data["summary"]=reason;assert not eligible(data)[0]


def test_source_build_current_version_and_reproduction_are_required():
    assert eligible(report())[0]
    mutations=[lambda r:r["facts"].update(reproduction=""),lambda r:r["facts"].update(version="1.2.0"),
               lambda r:r["published_build"].update(source_has_uncommitted_changes=True),
               lambda r:r["published_build"].update(source_commit=""),lambda r:r["published_build"].update(checked_at=1)]
    for mutate in mutations:
        data=report();mutate(data);assert not eligible(data)[0]


def test_beta_and_wrong_numeric_build_never_enter_automatic_fixing():
    data=report();data["facts"]["distribution"]="beta";assert not eligible(data)[0]
    data=report();data["published_build"]["platforms"]["windows"]["build"]="123"
    data["facts"]["build"]="122";assert not eligible(data)[0]
    data["facts"]["build"]="123";assert eligible(data)[0]


def patch(path="src/calc.py",line="return x + 1"):
    return f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -1,2 +1,2 @@\n def total(x):\n-    return x - 1\n+    {line}\n"


@pytest.mark.parametrize("path",["../escape.py","/tmp/escape.py",".github/workflows/x.yml","src/auth/login.py","src/migrations/a.py","src/AGENTS.md","other/calc.py","src/package.json"])
def test_patch_gate_rejects_unsafe_or_structural_paths(path):
    with pytest.raises(ValueError):validate_patch(patch(path),["src"])


def test_patch_size_modes_and_deletions_are_bounded():
    assert validate_patch(patch(),["src"])==["src/calc.py"]
    for value in (patch()+"\nnew file mode 120000\n",patch()+"\ndeleted file mode 100644\n",patch(line="x")+"\n"+"+lots\n"*121):
        with pytest.raises(ValueError):validate_patch(value,["src"])


def git(folder,*args):
    return subprocess.run(["git",*args],cwd=folder,check=True,capture_output=True,text=True).stdout.strip()


@pytest.fixture
def runner_fixture(tmp_path):
    repo=tmp_path/"repo";repo.mkdir();(repo/"src").mkdir();(repo/"tests").mkdir()
    (repo/"src/calc.py").write_text("def total(x):\n    return x - 1\n")
    (repo/"tests/test_baseline.py").write_text("def test_baseline():\n    assert True\n")
    (repo/".gitignore").write_text("__pycache__/\n.pytest_cache/\n")
    git(repo,"init","-q");git(repo,"add",".")
    git(repo,"-c","user.name=Test","-c","user.email=test@localhost","-c","commit.gpgsign=false","commit","-qm","baseline")
    commit=git(repo,"rev-parse","HEAD")
    policy={"repository":str(repo),"output_dir":str(tmp_path/"proposals"),"codex_home":str(tmp_path/"auth"),"source_commit":commit,
            "manifest_sha256":"b"*64,"test_image":"test@sha256:"+"c"*64,"codex_image":"codex@sha256:"+"d"*64,"codex_network":"torve-codex-egress","isolated_runner_ack":True,
            "allowed_categories":["state_bug"],"code_prefixes":["src"],"test_prefixes":["tests"],"checks":{"regression":["python","-m","pytest","tests"]}}
    test_patch="diff --git a/tests/test_regression.py b/tests/test_regression.py\nnew file mode 100644\n--- /dev/null\n+++ b/tests/test_regression.py\n@@ -0,0 +1,3 @@\n+from src.calc import total\n+def test_regression():\n+    assert total(1) == 2\n"
    assessment={"valid_bug":True,"lawful":True,"small":True,"no_structural_change":True,"category":"state_bug","check":"regression","evidence":"wrong arithmetic","regression_patch":test_patch}
    review={"approved":True,"lawful":True,"small":True,"preserves_existing_behavior":True,"reason":"narrow change"}
    outputs=[assessment,{"patch":patch(),"explanation":"Correct arithmetic"},review]
    calls=[]
    def agent(*args):calls.append(args);return outputs.pop(0)
    runner=Runner(policy,agent=agent)
    checks=[0,1,0]
    runner.tests=lambda w,n:(checks.pop(0),"offline fixture")
    data=report();data["published_build"]["source_commit"]=commit
    return runner,data,repo,outputs,calls,checks


def test_end_to_end_codex_proposal_requires_failing_regression_then_pass_and_review(runner_fixture):
    runner,data,repo,outputs,calls,checks=runner_fixture
    result=runner.propose(data)
    assert result["state"]=="proposal_ready" and result["deployed"] is False
    assert all(result["checks"].values()) and len(calls)==3
    assert not git(repo,"status","--porcelain")
    assert "x - 1" in (repo/"src/calc.py").read_text()


def test_real_synthetic_regression_fails_then_passes_in_disposable_source(runner_fixture):
    runner,data,repo,outputs,calls,checks=runner_fixture
    # This is a trusted synthetic two-line fixture, never the Torve application
    # test tree or its account DB. Production checks always use the container.
    results=[]
    def local_fixture_check(workspace,name):
        env={k:os.environ[k] for k in ("PATH","SYSTEMROOT","WINDIR") if k in os.environ}
        env["PYTHONPATH"]=str(workspace)
        r=subprocess.run([sys.executable,"-m","pytest","tests","--confcutdir=tests","-q","-p","no:cacheprovider"],cwd=workspace,env=env,capture_output=True,text=True,timeout=30)
        results.append(r.returncode)
        return r.returncode,r.stdout
    runner.tests=local_fixture_check
    result=runner.propose(data)
    assert result["state"]=="proposal_ready" and results==[0,1,0]
    assert not git(repo,"status","--porcelain")


def test_non_reproducing_regression_never_requests_a_fix(runner_fixture):
    runner,data,repo,outputs,calls,checks=runner_fixture;checks[:]=[0,0]
    assert runner.propose(data)["state"]=="human_review"
    assert len(calls)==1


def test_codex_cannot_self_approve_major_or_unlawful_fix(runner_fixture):
    runner,data,repo,outputs,calls,checks=runner_fixture;outputs[0]["no_structural_change"]=False
    assert runner.propose(data)["state"]=="human_review" and len(calls)==1


def test_source_provenance_mismatch_stops_before_codex(runner_fixture):
    runner,data,repo,outputs,calls,checks=runner_fixture;data["published_build"]["manifest_sha256"]="d"*64
    assert runner.propose(data)["state"]=="human_review" and not calls


def test_failed_independent_review_does_not_publish_artifact(runner_fixture):
    runner,data,repo,outputs,calls,checks=runner_fixture;outputs[2]["approved"]=False
    assert runner.propose(data)["state"]=="human_review"
    assert not (repo.parent/"proposals").exists()


def test_runner_rejects_missing_isolation_unpinned_images_and_broad_scope(runner_fixture):
    runner,*_=runner_fixture
    for change in ({"isolated_runner_ack":False},{"test_image":"latest"},{"code_prefixes":["."]},{"source_commit":"HEAD"}):
        policy=copy.deepcopy(runner.policy);policy.update(change)
        with pytest.raises(ValueError):check_policy(policy)
