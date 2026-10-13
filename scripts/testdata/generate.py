"""Generates realistic Chess.com-format monthly archives for local testing.

Games are played by the real Stockfish binary at low skill levels so they contain genuine
inaccuracies, mistakes and blunders. Output: data/archives.json + data/YYYY-MM.json.
"""
import json, random, subprocess, sys, os, time, uuid, datetime
import chess, chess.pgn

SF = sys.argv[1]
NNUE = sys.argv[2]
OUT = sys.argv[3]
USER = "aftercheck_tester"
random.seed(7)

OPPONENTS = ["knightowl", "PawnStorm88", "rookie_rex", "QueenSideQ", "e4ever", "TacticalTina", "slowmover", "BishopBash"]
OPENINGS = [["e4", "e5", "Nf3", "Nc6", "Bc4"], ["e4", "c5", "Nf3", "d6"], ["d4", "d5", "c4", "e6"], ["e4", "e6", "d4", "d5"],
            ["d4", "Nf6", "c4", "g6"], ["e4", "c6", "d4", "d5"], ["Nf3", "d5", "g3"], ["e4", "e5", "Nf3", "Nc6", "Bb5", "a6"]]
TIME = [("blitz", "180+2", 180, 2), ("rapid", "600", 600, 0), ("bullet", "60", 60, 0), ("blitz", "300", 300, 0)]


class Engine:
    def __init__(self):
        self.p = subprocess.Popen([SF], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        self.send("uci"); self.wait("uciok")
        self.send(f"setoption name EvalFile value {NNUE}")
        self.send("setoption name Threads value 2")
        self.send("isready"); self.wait("readyok")

    def send(self, c): self.p.stdin.write(c + "\n"); self.p.stdin.flush()

    def wait(self, token):
        while True:
            line = self.p.stdout.readline().strip()
            if line.startswith(token): return line

    def move(self, board, skill, ms):
        self.send(f"setoption name Skill Level value {skill}")
        self.send("position fen " + board.fen())
        self.send(f"go movetime {ms}")
        return self.wait("bestmove").split()[1]


def play(engine, white_skill, black_skill, opening):
    board = chess.Board()
    for san in opening:
        board.push_san(san)
    while not board.is_game_over(claim_draw=True) and board.ply() < 160:
        skill = white_skill if board.turn == chess.WHITE else black_skill
        uci = engine.move(board, skill, 40)
        board.push_uci(uci)
    return board


def to_pgn(board, white, black, w_elo, b_elo, tc, base, inc, end_dt, result, termination, url):
    game = chess.pgn.Game.from_board(board)
    h = game.headers
    h["Event"] = "Live Chess"; h["Site"] = "Chess.com"; h["Date"] = end_dt.strftime("%Y.%m.%d"); h["Round"] = "-"
    h["White"] = white; h["Black"] = black; h["Result"] = result
    h["WhiteElo"] = str(w_elo); h["BlackElo"] = str(b_elo); h["TimeControl"] = tc
    h["Termination"] = termination; h["EndTime"] = end_dt.strftime("%H:%M:%S GMT+0000"); h["Link"] = url
    # Clock comments as Chess.com writes them.
    clocks = [float(base), float(base)]
    node = game
    i = 0
    while node.variations:
        node = node.variations[0]
        side = i % 2
        spent = max(0.3, random.gauss(base / 60.0, base / 90.0))
        clocks[side] = max(0.2, clocks[side] - spent + inc)
        c = clocks[side]
        node.comment = "[%%clk %d:%02d:%04.1f]" % (int(c // 3600), int(c % 3600 // 60), c % 60)
        i += 1
    exporter = chess.pgn.StringExporter(headers=True, variations=False, comments=True)
    return game.accept(exporter)


def main():
    os.makedirs(OUT, exist_ok=True)
    engine = Engine()
    now = datetime.datetime.now(datetime.timezone.utc)
    months = {}
    n_games = int(os.environ.get("GAMES", "24"))
    for i in range(n_games):
        # Spread over the last ~100 days, newest first.
        end_dt = now - datetime.timedelta(days=i * 4 + random.random() * 3, hours=random.random() * 10)
        opp = random.choice(OPPONENTS)
        user_white = random.random() < 0.5
        user_skill, opp_skill = random.choice([3, 5, 6, 8]), random.choice([2, 4, 6, 9])
        ws, bs = (user_skill, opp_skill) if user_white else (opp_skill, user_skill)
        board = play(engine, ws, bs, random.choice(OPENINGS))
        tclass, tc, base, inc = random.choice(TIME)
        outcome = board.outcome(claim_draw=True)
        if outcome is None:
            # Ran out of plies: the side that is worse "resigns" (decide by material).
            mat = sum(len(board.pieces(p, chess.WHITE)) * v - len(board.pieces(p, chess.BLACK)) * v for p, v in [(1, 1), (2, 3), (3, 3), (4, 5), (5, 9)])
            winner = chess.WHITE if mat >= 0 else chess.BLACK
            reason_loser = "resigned"
        else:
            winner = outcome.winner
            reason_loser = "checkmated" if outcome.termination == chess.Termination.CHECKMATE else None
        white = USER if user_white else opp
        black = opp if user_white else USER
        u_elo, o_elo = 1100 + random.randint(-60, 80), 1100 + random.randint(-150, 150)
        w_elo, b_elo = (u_elo, o_elo) if user_white else (o_elo, u_elo)
        gid = str(uuid.uuid4())
        url = f"https://www.chess.com/game/live/{10**10 + i}"
        if winner is None:
            result, wr, br = "1/2-1/2", "repetition" if outcome and outcome.termination == chess.Termination.THREEFOLD_REPETITION else "stalemate" if outcome and outcome.termination == chess.Termination.STALEMATE else "insufficient", None
            br = wr
            term = "Game drawn"
        elif winner == chess.WHITE:
            result, wr, br = "1-0", "win", reason_loser
            term = f"{white} won by {'checkmate' if reason_loser == 'checkmated' else 'resignation'}"
        else:
            result, wr, br = "0-1", reason_loser, "win"
            term = f"{black} won by {'checkmate' if reason_loser == 'checkmated' else 'resignation'}"
        pgn = to_pgn(board, white, black, w_elo, b_elo, tc, base, inc, end_dt, result, term, url)
        entry = {
            "url": url, "pgn": pgn, "time_control": tc, "end_time": int(end_dt.timestamp()), "rated": True,
            "uuid": gid, "initial_setup": chess.STARTING_FEN, "fen": board.fen(), "time_class": tclass, "rules": "chess",
            "white": {"rating": w_elo, "result": wr, "@id": f"https://api.chess.com/pub/player/{white.lower()}", "username": white, "uuid": "w" + gid[:8]},
            "black": {"rating": b_elo, "result": br, "@id": f"https://api.chess.com/pub/player/{black.lower()}", "username": black, "uuid": "b" + gid[:8]},
        }
        key = end_dt.strftime("%Y-%m")
        months.setdefault(key, []).append(entry)
        print(f"game {i + 1}/{n_games}: {board.ply()} plies {result} {tclass}", flush=True)
    # A Chess960 game to check variant skipping.
    some = sorted(months)[-1]
    months[some].append(dict(months[some][0], uuid=str(uuid.uuid4()), rules="chess960", url="https://www.chess.com/game/daily/960"))
    for key, games in months.items():
        games.sort(key=lambda g: g["end_time"])
        with open(os.path.join(OUT, key + ".json"), "w") as f:
            json.dump({"games": games}, f)
    with open(os.path.join(OUT, "months.json"), "w") as f:
        json.dump(sorted(months), f)
    engine.send("quit")


main()
