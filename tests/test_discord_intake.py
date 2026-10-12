import asyncio
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from discord_intake import (
    DISCORD_LABEL, Intake, Linear, PROJECT, REPO, SCHEMA, Store, TEAM,
    LOCAL_CONFIG, analyze, codex_env, load_config, outside_repo, run_discord,
    describe, main, setup_config, verify_subscription,
)


def decision(action="draft", **changes):
    result = dict(action=action, message="What happened before the failure?", title="PDF import loses sources",
                  observations="User reports lost sources after PDF import.",
                  code_findings="pdf_search.py:1 associates sources with results; failure not reproduced.",
                  uncertainty="Device details are unknown.", issue_id="")
    result.update(changes)
    return result


def message(number, text, reply=None, owner=42, channel=100, dm=False, bot=False, mentions=()):
    return SimpleNamespace(id=number, content=text, jump_url=f"https://discord.com/channels/1/100/{number}",
                           reference=SimpleNamespace(message_id=reply) if reply else None,
                           author=SimpleNamespace(id=owner, bot=bot),
                           channel=SimpleNamespace(id=channel), guild=None if dm else SimpleNamespace(id=1),
                           mentions=[SimpleNamespace(id=user_id) for user_id in mentions])


class FakeLinear:
    def __init__(self):
        self.rows, self.created, self.comments = [], [], []
        self.lose_response = False

    def issues(self):
        return self.rows.copy()

    def create(self, report, description):
        issue = dict(id=report["issue_uuid"], identifier="SCH-99", url="https://linear.app/schuele/issue/SCH-99",
                     description=description)
        self.rows.append(issue)
        self.created.append(issue)
        if self.lose_response:
            raise RuntimeError("Response lost after server committed.")
        return issue

    def add_evidence(self, report, issue, description):
        self.comments.append((report["comment_uuid"], issue["id"], description))


class IntakeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "state.sqlite3"
        self.store = Store(self.path)
        self.addCleanup(self.store.db.close)
        self.config = dict(discord_token="discord-secret", linear_api_key="linear-secret", owner_id=42,
                           mode="channel", channel_id=100, trigger="command", filing="confirm")
        self.linear = FakeLinear()
        self.results, self.calls = [decision()], []

        def analyzer(report, issues, secrets):
            self.calls.append((json.loads(json.dumps(report)), issues))
            return self.results.pop(0) if len(self.results) > 1 else self.results[0].copy()

        self.intake = Intake(self.config, self.store, self.linear, analyzer)

    def handle(self, number, text, reply=None, **kw):
        return self.intake.handle(message(number, text, reply, **kw), 500)

    def test_draft_confirmation_and_replay_after_restart(self):
        report, text = self.handle(1, "!report PDF sources disappear")
        self.assertIn("Reply !create", text)
        self.assertFalse(self.linear.created)
        self.store.bind(10, report["id"])
        reopened = Store(self.path)
        self.addCleanup(reopened.db.close)
        self.intake.store = reopened
        _, text = self.handle(2, "!create", reply=10)
        self.assertIn("Filed:", text)
        self.assertIn(report["source"], self.linear.created[0]["description"])
        self.assertEqual(self.handle(1, "!report PDF sources disappear")[1], text)
        self.handle(2, "!create", reply=10)
        self.assertEqual(len(self.linear.created), 1)

    def test_clarification_retains_question_and_user_intent(self):
        self.results = [decision("question"), decision()]
        report, _ = self.handle(1, "<@500> PDF sources disappear")
        self.store.bind(10, report["id"])
        _, text = self.handle(2, "After importing on Android", reply=10)
        self.assertIn("Reply !create", text)
        self.assertEqual(self.calls[-1][0]["conversation"], [
            {"role": "user", "text": "PDF sources disappear"},
            {"role": "assistant", "text": "What happened before the failure?"},
            {"role": "user", "text": "After importing on Android"},
        ])
        self.assertFalse(self.linear.created)

    def test_duplicate_requires_explicit_add_and_never_creates(self):
        self.linear.rows = [dict(id="existing", description="Earlier bug", url="https://linear.app/schuele/issue/SCH-2")]
        self.results = [decision("duplicate", issue_id="existing")]
        report, text = self.handle(1, "!report PDF sources disappear")
        self.assertIn("SCH-2", text)
        self.assertIn("Reply !add", text)
        self.assertFalse(self.linear.comments)
        self.store.bind(10, report["id"])
        self.handle(2, "!add", reply=10)
        self.handle(2, "!add", reply=10)
        self.assertEqual(len(self.linear.comments), 1)
        self.assertFalse(self.linear.created)

    def test_automatic_mode_recovers_lost_create_response_without_new_uuid(self):
        self.config["filing"] = "automatic"
        self.linear.lose_response = True
        with self.assertRaises(RuntimeError):
            self.handle(1, "!report PDF sources disappear")
        issue_uuid = self.store.get(1)["issue_uuid"]
        _, text = self.handle(2, "!retry", reply=1)
        self.assertIn("Filed:", text)
        self.assertEqual(self.linear.created[0]["id"], issue_uuid)
        self.assertEqual(len(self.linear.created), 1)

    def test_final_duplicate_check_blocks_stale_confirmation(self):
        report, _ = self.handle(1, "!report PDF sources disappear")
        self.store.bind(10, report["id"])
        self.linear.rows = [dict(id="existing", description="Similar bug", url="https://linear.app/schuele/issue/SCH-2")]
        self.results = [decision("duplicate", issue_id="existing")]
        _, text = self.handle(2, "!create", reply=10)
        self.assertIn("Triage changed", text)
        self.assertFalse(self.linear.created)
        self.assertIn("SCH-2", self.handle(3, "!retry", reply=2)[1])

    def test_owner_channel_trigger_and_dm_boundaries(self):
        for kwargs in (dict(owner=43), dict(channel=101), dict(dm=True), dict(bot=True)):
            self.assertIsNone(self.handle(1, "!report secret", **kwargs))
        self.assertIsNone(self.handle(1, "ordinary chat"))
        self.config.update(mode="dm", trigger="all")
        self.assertIsNone(self.handle(1, "!report server message"))
        self.assertIsNotNone(self.handle(2, "A rough idea", dm=True))

    def test_mention_only_intake_requires_mentions_in_initial_and_followup_messages(self):
        self.config.update(trigger="mention", filing="automatic", guild_id=1)
        self.results = [decision("question"), decision()]
        self.assertIsNone(self.handle(1, "!report a bug"))
        self.assertIsNone(self.handle(2, "ordinary chat"))
        report, text = self.handle(3, "PDF sources disappear <@500>")
        self.assertIn("reply ping", text)
        self.store.bind(10, report["id"])
        self.assertIsNone(self.handle(4, "After importing on Android", reply=10))
        _, text = self.handle(5, "<@!500> After importing on Android", reply=10)
        self.assertIn("Filed:", text)
        self.assertEqual(len(self.linear.created), 1)
        self.assertNotIn("<@", self.calls[-1][0]["conversation"][-1]["text"])
        self.handle(5, "<@!500> After importing on Android", reply=10)
        self.assertEqual(len(self.linear.created), 1)

    def test_discord_reply_ping_continues_report_without_mention_in_content(self):
        self.config.update(trigger="mention", filing="automatic")
        self.results = [decision("question"), decision()]
        report, _ = self.handle(1, "<@500> PDF sources disappear")
        self.store.bind(10, report["id"])
        self.assertIsNone(self.handle(2, "reply without ping", reply=10, mentions=(42,)))
        with self.assertLogs(level="INFO") as logs:
            report, text = self.handle(3, "After import discord-secret linear-secret", reply=10, mentions=(500,))
        self.assertIn("Filed:", text)
        self.assertEqual(report["id"], "1")
        self.assertEqual(len(self.linear.created), 1)
        clarification = [turn["text"] for turn in self.calls[-1][0]["conversation"] if turn["role"] == "user"][-1]
        self.assertEqual(clarification, "After import [REDACTED] [REDACTED]")
        self.assertNotIn("discord-secret", str(logs.output))
        self.assertNotIn("linear-secret", str(logs.output))
        self.assertNotIn("After import", str(logs.output))
        self.assertIn("linear_filed report_id=1", str(logs.output))

    def test_status_reads_saved_decision_without_authentication_or_network(self):
        self.results = [decision("question")]
        self.handle(1, "!report incomplete bug")
        output = io.StringIO()
        with patch("sys.argv", ["discord_intake.py", "--state", str(self.path), "--status"]), \
                patch("discord_intake.load_config") as config, \
                patch("discord_intake.verify_subscription") as subscription, \
                contextlib.redirect_stdout(output):
            main()
        config.assert_not_called()
        subscription.assert_not_called()
        report = json.loads(output.getvalue())[0]
        self.assertEqual(report["id"], "1")
        self.assertEqual(report["decision"]["action"], "question")
        self.assertIsNone(report["done"])

    def test_server_boundary(self):
        self.config["guild_id"] = 2
        self.assertIsNone(self.handle(1, "<@500> bug"))

    def test_final_check_files_the_refreshed_description(self):
        self.config["filing"] = "automatic"
        self.results = [decision(), decision(observations="More precise evidence from the final check.")]
        self.handle(1, "<@500> PDF sources disappear")
        self.assertIn("More precise evidence", self.linear.created[0]["description"])

    def test_task_brief_omits_empty_sections_and_routine_disclaimers(self):
        report = dict(source="https://discord.com/channels/1/100/1", decision=decision(
            observations="Generate explanatory diagrams from source-enriched prompts.\n\n- Prefer on-device generation.",
            code_findings="Models.kt:64 currently exposes text generation.", uncertainty=""))
        text = describe(report)
        self.assertTrue(text.startswith("Generate explanatory diagrams"))
        self.assertIn("## Implementation context", text)
        self.assertNotIn("## Open questions", text)
        self.assertNotIn("None identified", text)
        self.assertNotIn("not reproduced", text)
        self.assertEqual(text.count(report["source"]), 1)
        report["decision"].update(code_findings="", uncertainty="Which on-device approach is feasible?")
        text = describe(report)
        self.assertNotIn("## Implementation context", text)
        self.assertIn("## Open questions\n\nWhich on-device approach is feasible?", text)

    def test_attachment_only_report_keeps_uninspected_evidence_explicit(self):
        self.results = [decision("question", message="Please describe the attached failure in text.")]
        incoming = message(1, "!report")
        incoming.attachments = [SimpleNamespace(filename="screenshot.png")]
        report, text = self.intake.handle(incoming, 500)
        self.assertIn("not been inspected", report["conversation"][0]["text"])
        self.assertIn("describe", text)
        self.assertFalse(self.linear.created)

    def test_credentials_are_removed_before_persistence(self):
        report, text = self.handle(1, "!report discord-secret linear-secret")
        self.assertEqual(report["conversation"][0]["text"], "[REDACTED] [REDACTED]")
        self.assertNotIn("discord-secret", self.path.read_bytes().decode(errors="ignore"))
        self.assertNotIn("linear-secret", text)

    def test_cancel_and_replayed_clarification_do_not_run_again(self):
        self.results = [decision("question")]
        self.handle(1, "!report incomplete bug")
        self.handle(1, "!report incomplete bug")
        self.assertEqual(len(self.calls), 1)
        self.handle(2, "!cancel", reply=1)
        self.assertEqual(self.handle(3, "!retry", reply=2)[1], "Cancelled intake.")

    def test_concurrent_start_keeps_the_first_persisted_uuid(self):
        report, _ = self.handle(1, "!report incomplete bug")
        contender = dict(report, issue_uuid="other-uuid")
        reopened = Store(self.path)
        self.addCleanup(reopened.db.close)
        self.assertEqual(reopened.start(contender)["issue_uuid"], report["issue_uuid"])

    def test_gateway_reply_binding_runs_through_background_thread(self):
        events, sent = {}, []
        intake = self.intake

        class Channel:
            id = 100
            guild = SimpleNamespace(id=1)

            def permissions_for(self, member):
                return SimpleNamespace(view_channel=True, send_messages=True, read_message_history=True)

        async def fetch_member(user_id):
            return SimpleNamespace(id=user_id)

        Channel.guild.fetch_member = fetch_member

        async def fetch_channels():
            return [Channel()]

        Channel.guild.fetch_channels = fetch_channels

        class Client:
            def __init__(self, **kwargs):
                self.user = SimpleNamespace(id=500)

            def event(self, callback):
                events[callback.__name__] = callback
                return callback

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def login(self, token):
                pass

            async def fetch_channel(self, channel_id):
                return Channel()

            async def fetch_guild(self, guild_id):
                return Channel.guild

            async def connect(self, **kwargs):
                async def reply(text, **kwargs):
                    sent.append(text)
                    return SimpleNamespace(id=10 + len(sent))
                root = message(1, "!report PDF sources disappear")
                root.reply = reply
                await events["on_message"](root)
                followup = message(2, "!create", reply=11)
                followup.reply = reply
                await events["on_message"](followup)
                failed = message(3, "!report another bug")
                failed.reply = reply
                with patch.object(intake, "handle", side_effect=RuntimeError("discord-secret linear-secret")):
                    await events["on_message"](failed)

        discord = SimpleNamespace(Client=Client, TextChannel=Channel,
                                  Intents=SimpleNamespace(default=lambda: SimpleNamespace()),
                                  AllowedMentions=SimpleNamespace(none=lambda: None))
        with patch.dict("sys.modules", {"discord": discord}), self.assertLogs(level="INFO") as logs:
            asyncio.run(run_discord(self.config, intake))
        self.assertEqual(len(self.linear.created), 1)
        self.assertIn("Filed:", sent[-2])
        self.assertIn("Intake failed", sent[-1])
        self.assertIn("intake_failed message_id=3 error_type=RuntimeError", str(logs.output))
        self.assertNotIn("discord-secret", str(logs.output))
        self.assertNotIn("linear-secret", str(logs.output))


class BoundaryTests(unittest.TestCase):
    def test_discord_startup_rejects_wrong_application_and_missing_permissions(self):
        class Channel:
            id = 100

            def permissions_for(self, member):
                return SimpleNamespace(view_channel=True, send_messages=False, read_message_history=True)

        class Guild:
            async def fetch_channels(self):
                return [Channel()]

            async def fetch_member(self, user_id):
                return SimpleNamespace(id=user_id)

        class Client:
            user = SimpleNamespace(id=500)

            def __init__(self, **kwargs):
                self.intents = kwargs["intents"]

            def event(self, callback):
                return callback

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def login(self, token):
                pass

            async def application_info(self):
                return SimpleNamespace(id=500)

            async def fetch_guild(self, guild_id):
                return Guild()

            async def connect(self, **kwargs):
                raise AssertionError("Must not connect before validation passes.")

        discord = SimpleNamespace(Client=Client, TextChannel=Channel,
                                  Intents=SimpleNamespace(default=lambda: SimpleNamespace()),
                                  AllowedMentions=SimpleNamespace(none=lambda: None))
        config = dict(discord_token="secret", mode="channel", trigger="mention", channel_id=100,
                      guild_id=1, application_id=501)
        with patch.dict("sys.modules", {"discord": discord}):
            with self.assertRaisesRegex(ValueError, "different Discord application"):
                asyncio.run(run_discord(config, None))
            config["application_id"] = 500
            with self.assertRaisesRegex(ValueError, "Send Messages"):
                asyncio.run(run_discord(config, None))

    def test_local_setup_stores_private_config_and_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "private" / "config.json"
            responses = [SimpleNamespace(returncode=0, stdout="discord-secret\n"),
                         SimpleNamespace(returncode=0, stdout="linear-secret\n")]
            with patch("discord_intake.subprocess.run", side_effect=responses):
                setup_config(path)
            config = load_config(path)
            self.assertEqual(config["trigger"], "mention")
            self.assertEqual(config["filing"], "automatic")
            self.assertEqual(config["owner_id"], int(LOCAL_CONFIG["owner_id"]))
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
            with self.assertRaises(ValueError):
                setup_config(path)

    def test_cancelled_setup_saves_no_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "private" / "config.json"
            with patch("discord_intake.subprocess.run", return_value=SimpleNamespace(returncode=1, stdout="")):
                with self.assertRaises(ValueError):
                    setup_config(path)
            self.assertFalse(path.exists())

    def test_config_requires_explicit_decisions_and_private_external_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "credentials.json"
            config = dict(discord_token="discord-secret", linear_api_key="linear-secret", owner_id="42",
                          channel_id="100", mode="channel", trigger="command", filing="confirm")
            path.write_text(json.dumps(config))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                load_config(path)
            path.chmod(0o600)
            self.assertEqual(load_config(path)["owner_id"], 42)
            config["linear_api_key"] = config["discord_token"]
            path.write_text(json.dumps(config))
            with self.assertRaisesRegex(ValueError, "separate credentials"):
                load_config(path)
            config["linear_api_key"] = "linear-secret"
            del config["filing"]
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                load_config(path)
        with self.assertRaises(ValueError):
            outside_repo(REPO / "credentials.json")

    def test_linear_paginates_including_archived_and_scopes_every_page(self):
        linear = Linear("secret")
        pages = [dict(issues=dict(nodes=[dict(id="a")], pageInfo=dict(hasNextPage=True, endCursor="next"))),
                 dict(issues=dict(nodes=[dict(id="b")], pageInfo=dict(hasNextPage=False, endCursor=None)))]
        with patch.object(linear, "query", side_effect=pages) as query:
            self.assertEqual(linear.issues(), [dict(id="a"), dict(id="b")])
            for call in query.call_args_list:
                self.assertIn("includeArchived: true", call.args[0])
                self.assertEqual(call.args[1]["filter"],
                                 {"project": {"id": {"eq": PROJECT}}, "team": {"id": {"eq": TEAM}}})
            self.assertEqual(query.call_args_list[1].args[1]["after"], "next")

    def test_linear_create_applies_discord_label_with_title_description_destination_and_persisted_uuid(self):
        linear = Linear("secret")
        report = dict(issue_uuid="saved-uuid", decision=decision())
        with patch.object(linear, "query", return_value=dict(issueCreate=dict(success=True, issue=dict(id="saved-uuid")))) as query:
            linear.create(report, "description")
            self.assertEqual(set(query.call_args.args[1]["input"]),
                             {"id", "teamId", "projectId", "title", "description", "labelIds"})
            self.assertEqual(query.call_args.args[1]["input"]["labelIds"], [DISCORD_LABEL])

    def test_comment_recovery_skips_second_mutation(self):
        linear = Linear("secret")
        with patch.object(linear, "query", return_value=dict(comments=dict(nodes=[dict(id="saved")]))):
            linear.add_evidence(dict(comment_uuid="saved"), dict(id="issue"), "evidence")
            self.assertEqual(linear.query.call_count, 1)

    def test_codex_uses_subscription_without_inherited_secrets(self):
        with patch.dict(os.environ, {"DISCORD_BOT_TOKEN": "secret", "OPENAI_API_KEY": "secret", "LINEAR_API_KEY": "secret"}):
            self.assertNotIn("OPENAI_API_KEY", codex_env())
            self.assertNotIn("LINEAR_API_KEY", codex_env())
            self.assertNotIn("DISCORD_BOT_TOKEN", codex_env())
        with patch("discord_intake.subprocess.run", return_value=SimpleNamespace(returncode=0, stdout="", stderr="Logged in using an API key")):
            with self.assertRaises(RuntimeError):
                verify_subscription()

    def test_codex_schema_and_unverified_duplicate_rejection(self):
        def run(command, **kwargs):
            self.assertIn("--ignore-user-config", command)
            self.assertIn("--ephemeral", command)
            self.assertEqual(command[command.index("--sandbox") + 1], "read-only")
            self.assertEqual(json.loads(Path(command[command.index("--output-schema") + 1]).read_text()), SCHEMA)
            output = Path(command[command.index("-o") + 1])
            output.write_text(json.dumps(decision("duplicate", issue_id="invented")))
            return SimpleNamespace(returncode=0)
        with patch("discord_intake.subprocess.run", side_effect=run):
            with self.assertRaises(ValueError):
                analyze(dict(source="url", conversation=[]), [dict(id="real")])


if __name__ == "__main__":
    unittest.main()
